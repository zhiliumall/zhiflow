package com.zhiflow.common.data.cache;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-L1 名单（{@code zhiflow.perf.redis-l1.cache-names}）在 {@code RedisAutoCacheManager#getCache} 里动态生效。
 */
class RedisAutoCacheManagerL1Test {

	private MockEnvironment environment;

	private RedisAutoCacheManager manager;

	private InMemoryRedisCacheWriter redis;

	@BeforeEach
	void setUp() {
		this.environment = new MockEnvironment();
		this.redis = new InMemoryRedisCacheWriter();
		this.manager = manager(this.redis, this.environment);
	}

	@AfterEach
	void clearTenant() {
		TenantContextHolder.clear();
	}

	private static RedisAutoCacheManager manager(InMemoryRedisCacheWriter writer, MockEnvironment environment) {
		RedisCacheConfiguration config = TwoLevelRedisCacheTest.config();
		Map<String, RedisCacheConfiguration> initial = new LinkedHashMap<>();
		initial.put(CacheConstants.MENU_CACHE, config);
		initial.put(CacheConstants.USER_CACHE, config);
		RedisAutoCacheManager manager = new RedisAutoCacheManager(writer, config, initial, true, null,
				new RedisL1Settings(environment, 0));
		manager.afterPropertiesSet();
		return manager;
	}

	@Test
	@DisplayName("默认（名单空）：哪里都没有 L1，拿到的仍是 DefensiveRedisCache")
	void emptyListMeansNoL1() {
		assertInstanceOf(DefensiveRedisCache.class, this.manager.getCache(CacheConstants.MENU_CACHE));
		assertInstanceOf(DefensiveRedisCache.class, this.manager.getCache("public:dict_cache"));
	}

	@Test
	@DisplayName("名单现读：加上即包 L1（同名复用同一个包装），去掉即恢复，不用重启")
	void listIsReadDynamically() {
		this.environment.setProperty(RedisL1Settings.CACHE_NAMES_PROPERTY, CacheConstants.MENU_CACHE);
		Cache wrapped = this.manager.getCache(CacheConstants.MENU_CACHE);
		assertInstanceOf(TwoLevelRedisCache.class, wrapped);
		assertSame(wrapped, this.manager.getCache(CacheConstants.MENU_CACHE));
		// 对照：没列出的缓存名不受影响
		assertInstanceOf(DefensiveRedisCache.class, this.manager.getCache("public:dict_cache"));

		this.environment.setProperty(RedisL1Settings.CACHE_NAMES_PROPERTY, "");
		assertInstanceOf(DefensiveRedisCache.class, this.manager.getCache(CacheConstants.MENU_CACHE));
	}

	@Test
	@DisplayName("YAML 列表写法同样认")
	void yamlListForm() {
		this.environment.setProperty(RedisL1Settings.CACHE_NAMES_PROPERTY + "[0]", CacheConstants.MENU_CACHE);
		assertInstanceOf(TwoLevelRedisCache.class, this.manager.getCache(CacheConstants.MENU_CACHE));
	}

	@Test
	@DisplayName("user_cache / 令牌缓存即使被写进名单也不加 L1（值读出来后会被改）")
	void deniedNamesAreNeverWrapped() {
		this.environment.setProperty(RedisL1Settings.CACHE_NAMES_PROPERTY,
				CacheConstants.USER_CACHE + "," + CacheConstants.OAUTH_CLIENT_CACHE + "," + CacheConstants.MENU_CACHE);

		assertInstanceOf(DefensiveRedisCache.class, this.manager.getCache(CacheConstants.USER_CACHE));
		assertInstanceOf(DefensiveRedisCache.class, this.manager.getCache(CacheConstants.OAUTH_CLIENT_CACHE));
		// 对照：同一份名单里的菜单缓存照常包
		assertInstanceOf(TwoLevelRedisCache.class, this.manager.getCache(CacheConstants.MENU_CACHE));
	}

	@Test
	@DisplayName("L1 开着时管理器内部仍存 RedisCache：getCacheConfigurations() 的强转不炸")
	void cacheMapStillHoldsRedisCaches() {
		this.environment.setProperty(RedisL1Settings.CACHE_NAMES_PROPERTY, CacheConstants.MENU_CACHE);
		this.manager.getCache(CacheConstants.MENU_CACHE);

		assertDoesNotThrow(() -> this.manager.getCacheConfigurations());
	}

	@Test
	@DisplayName("带租户前缀的缓存名按去掉前缀后的名字匹配名单；包装与代号每租户一份")
	void tenantPrefixedNameMatchesWhitelist() {
		this.environment.setProperty(RedisL1Settings.CACHE_NAMES_PROPERTY, CacheConstants.MALL_GOODS_DETAIL_CACHE);

		TenantContextHolder.setTenantId("1");
		Cache tenant1 = this.manager.getCache(CacheConstants.MALL_GOODS_DETAIL_CACHE);
		assertInstanceOf(TwoLevelRedisCache.class, tenant1);
		assertEquals("1:" + CacheConstants.MALL_GOODS_DETAIL_CACHE, tenant1.getName());
		assertSame(tenant1, this.manager.getCache(CacheConstants.MALL_GOODS_DETAIL_CACHE));

		TenantContextHolder.setTenantId("2");
		Cache tenant2 = this.manager.getCache(CacheConstants.MALL_GOODS_DETAIL_CACHE);
		assertInstanceOf(TwoLevelRedisCache.class, tenant2);
		assertNotSame(tenant1, tenant2);

		// 代号键在各自租户前缀下
		tenant1.put("100", "snapshot-1");
		tenant1.get("100");
		tenant2.put("100", "snapshot-2");
		tenant2.get("100");
		assertTrue(this.redis.store.containsKey("1:" + CacheConstants.MALL_GOODS_DETAIL_CACHE + "::__l1_gen"));
		assertTrue(this.redis.store.containsKey("2:" + CacheConstants.MALL_GOODS_DETAIL_CACHE + "::__l1_gen"));
	}

	@Test
	@DisplayName("名单写带租户前缀的名字不认（名单只写 @Cacheable 上的名字）")
	void prefixedNameInListIsIgnored() {
		this.environment.setProperty(RedisL1Settings.CACHE_NAMES_PROPERTY, "1:" + CacheConstants.MALL_GOODS_DETAIL_CACHE);
		TenantContextHolder.setTenantId("1");

		assertInstanceOf(DefensiveRedisCache.class, this.manager.getCache(CacheConstants.MALL_GOODS_DETAIL_CACHE));
	}

	@Test
	@DisplayName("库存缓存 mall:goods_stock#5 写进名单也不加 L1（只靠 TTL 过期，代号感知不到）")
	void stockCacheIsNeverWrapped() {
		this.environment.setProperty(RedisL1Settings.CACHE_NAMES_PROPERTY,
				CacheConstants.MALL_GOODS_STOCK_CACHE + "," + CacheConstants.MALL_GOODS_DETAIL_CACHE);
		TenantContextHolder.setTenantId("1");

		assertInstanceOf(DefensiveRedisCache.class, this.manager.getCache(CacheConstants.MALL_GOODS_STOCK_CACHE));
		// 对照：同一份名单里的详情快照照常包
		assertInstanceOf(TwoLevelRedisCache.class, this.manager.getCache(CacheConstants.MALL_GOODS_DETAIL_CACHE));
		assertTrue(RedisL1Settings.DENIED.contains(CacheConstants.MALL_GOODS_STOCK_CACHE));
		assertFalse(RedisL1Settings.allowsL1("1:" + CacheConstants.MALL_GOODS_STOCK_CACHE));
	}

	@Test
	@DisplayName("跨进程：读方开 L1、清方没开 L1，清方 evict 照样删代号，读方下次不再给旧对象")
	void evictFromProcessWithoutL1StillInvalidatesReaders() {
		MockEnvironment readerEnv = new MockEnvironment()
			.withProperty(RedisL1Settings.CACHE_NAMES_PROPERTY, CacheConstants.MALL_GOODS_DETAIL_CACHE);
		RedisAutoCacheManager reader = manager(this.redis, readerEnv);
		RedisAutoCacheManager admin = manager(this.redis, new MockEnvironment());
		TenantContextHolder.setTenantId("1");

		Cache readerCache = reader.getCache(CacheConstants.MALL_GOODS_DETAIL_CACHE);
		Cache adminCache = admin.getCache(CacheConstants.MALL_GOODS_DETAIL_CACHE);
		assertInstanceOf(TwoLevelRedisCache.class, readerCache);
		assertInstanceOf(DefensiveRedisCache.class, adminCache);

		readerCache.put("100", "old-name");
		Object first = readerCache.get("100").get();
		assertSame(first, readerCache.get("100").get(), "判据自检：命中 L1 拿到同一实例");

		adminCache.evict("100");

		assertFalse(this.redis.store.containsKey("1:" + CacheConstants.MALL_GOODS_DETAIL_CACHE + "::__l1_gen"));
		assertNull(readerCache.get("100"), "L2 已删、代号已删：读方不能再从 L1 给出旧值");
	}

	@Test
	@DisplayName("详情快照按值自报 KB 计权；菜单仍按元素数")
	void policiesPerCacheName() {
		assertSame(TwoLevelRedisCache.Policy.BY_KB, RedisL1Settings.policyOf("1:" + CacheConstants.MALL_GOODS_DETAIL_CACHE));
		assertSame(TwoLevelRedisCache.Policy.BY_ELEMENTS, RedisL1Settings.policyOf(CacheConstants.MENU_CACHE));
		assertNull(RedisL1Settings.policyOf("1:mall:goods_brand#1800"));
		assertEquals(7, TwoLevelRedisCache.weighKb((L1Weighted) () -> 7));
		assertEquals(1, TwoLevelRedisCache.weighKb((L1Weighted) () -> 0));
		assertEquals(3, TwoLevelRedisCache.weighKb(java.util.List.of("a", "b")));
	}

}
