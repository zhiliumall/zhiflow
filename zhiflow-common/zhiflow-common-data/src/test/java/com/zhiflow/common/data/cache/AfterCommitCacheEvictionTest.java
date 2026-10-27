package com.zhiflow.common.data.cache;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AfterCommitCacheEviction}：清缓存必须发生在事务提交<b>之后</b>。
 *
 * <p>模型：一个「库」（提交前别的连接只看得见已提交的值）+ 一个缓存 + 一个在事务进行中进来的并发读者
 * （{@code @Cacheable} 的 cache-aside：未命中就读已提交数据并写回缓存）。
 *
 * <p>第一个测试是<b>对照组</b>：按旧写法（事务里当场 {@code clear()}）跑同一个场景，断言提交后缓存里
 * <b>仍是旧值</b>。它要是不成立，说明这个模型本身就测不出问题，后面几条的「通过」也就不说明任何事。
 */
class AfterCommitCacheEvictionTest {

	private static final String NAME = CacheConstants.MENU_CACHE;

	private static final String KEY = "-1:1_menu";

	@AfterEach
	void clearTenant() {
		TenantContextHolder.clear();
	}

	/** 提交前，别的连接只看得见 committed。 */
	private static final class Db {
		volatile String committed = "OLD";
		String pending;

		void write(String value) {
			pending = value;
		}

		void commit() {
			if (pending != null) {
				committed = pending;
				pending = null;
			}
		}
	}

	/** 并发请求走 @Cacheable：命中直接返回；未命中读已提交数据并写回。 */
	private static String concurrentRead(Cache cache, Db db) {
		Cache.ValueWrapper hit = cache.get(KEY);
		if (hit != null) {
			return (String) hit.get();
		}
		String value = db.committed;
		cache.put(KEY, value);
		return value;
	}

	@Test
	@DisplayName("对照组：旧写法（事务里当场清）——提交前进来的请求把旧值写回，提交后缓存仍是旧值")
	void controlSampleOldOrderLeavesStaleValue() {
		Db db = new Db();
		CacheManager cm = new ConcurrentMapCacheManager();
		Cache cache = cm.getCache(NAME);
		cache.put(KEY, "OLD");
		TransactionTemplate tx = new CommitHookTransactionManager(db::commit).template();

		tx.executeWithoutResult(status -> {
			db.write("NEW");
			cache.clear();
			concurrentRead(cache, db);
		});

		assertEquals("NEW", db.committed, "库里已经是新值");
		assertEquals("OLD", cache.get(KEY).get(),
				"对照组必须复现问题：旧写法下提交后缓存里仍是旧值（生产上会保留到 2 小时 TTL）");
	}

	@Test
	@DisplayName("新写法：提交前不清（旧值此刻仍是已提交的事实），提交后清掉，下一次读到新值")
	void clearsOnlyAfterCommit() {
		Db db = new Db();
		CacheManager cm = new ConcurrentMapCacheManager();
		Cache cache = cm.getCache(NAME);
		cache.put(KEY, "OLD");
		TransactionTemplate tx = new CommitHookTransactionManager(db::commit).template();

		tx.executeWithoutResult(status -> {
			db.write("NEW");
			AfterCommitCacheEviction.clear(cm, NAME);
			assertEquals("OLD", concurrentRead(cache, db), "事务进行中，并发请求看到的仍是已提交的旧值");
			assertNotNull(cache.get(KEY), "提交前不得清");
		});

		assertNull(cache.get(KEY), "提交后必须已清——并发请求在提交前写回的值也一起清掉");
		assertEquals("NEW", concurrentRead(cache, db), "下一次请求读到新值");
	}

	@Test
	@DisplayName("回滚：不清（数据没变，旧缓存仍然正确）")
	void rollbackDoesNotClear() {
		Db db = new Db();
		CacheManager cm = new ConcurrentMapCacheManager();
		Cache cache = cm.getCache(NAME);
		cache.put(KEY, "OLD");
		TransactionTemplate tx = new CommitHookTransactionManager(db::commit).template();

		assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
			db.write("NEW");
			AfterCommitCacheEviction.clear(cm, NAME);
			throw new IllegalStateException("业务失败");
		}));

		assertEquals("OLD", cache.get(KEY).get());
	}

	@Test
	@DisplayName("没有事务：当场清（写库已自动提交）")
	void clearsImmediatelyWithoutTransaction() {
		CacheManager cm = new ConcurrentMapCacheManager();
		Cache cache = cm.getCache(NAME);
		cache.put(KEY, "OLD");

		AfterCommitCacheEviction.clear(cm, NAME);

		assertNull(cache.get(KEY));
	}

	@Test
	@DisplayName("单键 evict 同样推迟到提交后，且只删这一个键")
	void evictSingleKeyAfterCommit() {
		CacheManager cm = new ConcurrentMapCacheManager();
		Cache cache = cm.getCache(CacheConstants.USER_CACHE);
		cache.put("system", "OLD-USER");
		cache.put("admin", "OTHER-USER");
		TransactionTemplate tx = new CommitHookTransactionManager(() -> { }).template();

		tx.executeWithoutResult(status -> {
			AfterCommitCacheEviction.evict(cm, CacheConstants.USER_CACHE, "system");
			assertNotNull(cache.get("system"), "提交前不得删");
		});

		assertNull(cache.get("system"));
		assertEquals("OTHER-USER", cache.get("admin").get());
	}

	@Test
	@DisplayName("提交后清缓存失败：不向调用方抛（事务已提交，抛了只会变成「改成功了却报错」），且不影响后面那个缓存")
	void failureAfterCommitIsLoggedNotThrownAndDoesNotSkipTheRest() {
		Cache broken = new ConcurrentMapCache("broken") {
			@Override
			public void clear() {
				throw new IllegalStateException("redis down");
			}
		};
		ConcurrentMapCache user = new ConcurrentMapCache(CacheConstants.USER_CACHE);
		user.put("system", "OLD-USER");
		CacheManager cm = new CacheManager() {
			@Override
			public Cache getCache(String name) {
				return "broken".equals(name) ? broken : user;
			}

			@Override
			public Collection<String> getCacheNames() {
				return List.of("broken", CacheConstants.USER_CACHE);
			}
		};
		TransactionTemplate tx = new CommitHookTransactionManager(() -> { }).template();

		assertDoesNotThrow(() -> tx.executeWithoutResult(
				status -> AfterCommitCacheEviction.clear(cm, "broken", CacheConstants.USER_CACHE)));
		assertNull(user.get("system"), "前一个缓存清失败，后一个仍要清");
	}

	@Test
	@DisplayName("按指定租户清：清的是数据行租户前缀下的键，不是当前上下文的；上下文原样还原")
	void evictForTenantResolvesByGivenTenantAndRestoresContext() {
		TenantPrefixedCacheManager cm = new TenantPrefixedCacheManager();
		String name = "mall:goods_detail#600";
		TenantContextHolder.setTenantId("-1");
		cm.getCache(name).put("100", "CTX");
		TenantContextHolder.setTenantId("1");
		cm.getCache(name).put("100", "ROW");
		TenantContextHolder.setTenantId("-1");
		TransactionTemplate tx = new CommitHookTransactionManager(() -> { }).template();

		tx.executeWithoutResult(status -> {
			AfterCommitCacheEviction.evictForTenant(cm, "1", name, "100");
			assertEquals("-1", TenantContextHolder.getTenantId(), "解析完立即还原上下文");
			assertNotNull(cm.raw("1:" + name).get("100"), "提交前不得删");
		});

		assertNull(cm.raw("1:" + name).get("100"), "数据行租户前缀下的键提交后被清");
		assertEquals("CTX", cm.raw("-1:" + name).get("100").get(), "当前上下文前缀下的键不受影响");
		assertEquals("-1", TenantContextHolder.getTenantId());
	}

	@Test
	@DisplayName("按指定租户清：线程上原本没有租户时，清完仍然没有租户")
	void evictForTenantWithoutContextLeavesNoTenant() {
		TenantPrefixedCacheManager cm = new TenantPrefixedCacheManager();
		TenantContextHolder.setTenantId("1");
		cm.getCache(CacheConstants.MALL_SHOP_INFO_CACHE).put("1", "SHOP");
		TenantContextHolder.clear();

		AfterCommitCacheEviction.evictForTenant(cm, "1", CacheConstants.MALL_SHOP_INFO_CACHE, "1");

		assertNull(cm.raw("1:" + CacheConstants.MALL_SHOP_INFO_CACHE).get("1"), "没有事务：当场清");
		assertNull(TenantContextHolder.getTenantId(), "不能把临时设置的租户留在线程上");
	}

	/** 与 RedisAutoCacheManager 同样的命名规则：非 public: 缓存名在有租户时改名为 {tenantId}:{name}。 */
	private static final class TenantPrefixedCacheManager implements CacheManager {

		private final Map<String, Cache> caches = new ConcurrentHashMap<>();

		@Override
		public Cache getCache(String name) {
			String tenantId = TenantContextHolder.getTenantId();
			String actual = name.startsWith(CacheConstants.PUBLIC_CACHE) || tenantId == null ? name : tenantId + ":" + name;
			return raw(actual);
		}

		Cache raw(String actualName) {
			return caches.computeIfAbsent(actualName, ConcurrentMapCache::new);
		}

		@Override
		public Collection<String> getCacheNames() {
			return caches.keySet();
		}
	}
}
