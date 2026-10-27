package com.zhiflow.common.data.cache;

import com.zhiflow.common.data.tenant.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「清缓存」推迟到事务提交之后。
 *
 * <p><b>为什么不能在事务里当场清</b>：{@code SysRoleMenuServiceImpl.saveRoleMenus} 原先在
 * {@code @Transactional} 方法内部、{@code saveBatch} 之前就 {@code clear()} 了菜单与用户缓存。
 * 清完到提交之间只要有一个请求进来，它读到的是<b>还没提交的旧数据</b>，再把旧数据写回缓存——
 * 之后就一直按旧菜单 / 旧权限放行，最长到 TTL（2 小时）。{@code @CacheEvict} 叠
 * {@code @Transactional} 也一样：两个 advisor 都是 {@code LOWEST_PRECEDENCE}，谁包谁没有保证。
 * 全局 {@code transactionAware} 是关着的（{@code RedisCacheAutoConfiguration}），不去动它，只改这几处。
 *
 * <p>写法与 {@code SysTenantServiceImpl#provisionDefaultShopAfterCommit} 一致：
 * 有事务同步 ⇒ 注册 {@code afterCommit}；没有 ⇒ 当场执行（此时写库已自动提交）。
 * 回滚 ⇒ 不清（数据没变，旧缓存仍然是对的）。
 *
 * <p><b>要清的 {@link Cache} 在调用时就解析好</b>，不留到回调里：回调执行时线程上的租户上下文
 * 可能已被 {@code executeWithTenant} 还原，非 {@code public:} 缓存名会解析到别的租户前缀。
 *
 * <p><b>按数据行的租户清</b>（{@link #evictForTenant}）：后台改的是哪个租户的数据，就清哪个租户前缀下的键，
 * 不看当前线程上的租户——操作人的上下文与被改数据的租户不一定是同一个，按上下文清会清到别的前缀，
 * 用户端读的那个键原封不动。解析缓存时临时把租户上下文换成数据行的租户，解析完立即还原。
 *
 * <p>本类是「提交后清缓存」的唯一写法，upms 与商城后台共用；别再另写一套。
 *
 * <p><b>失败只记 error 不抛</b>：提交后再抛异常已无法回滚，只会制造「改成功了但接口报错」；
 * 与 {@code CacheFailureToleranceConfiguration} 对注解式清缓存失败的处理口径一致（那边是 warn，
 * 这里升 error：清失败意味着最长 2 小时的旧权限，必须能在日志里被发现）。
 */
@Slf4j
public final class AfterCommitCacheEviction {

	private AfterCommitCacheEviction() {
		throw new IllegalStateException("Utility class");
	}

	/**
	 * 事务提交后按给定顺序整体清空这些缓存；没有事务同步时当场清。
	 *
	 * <p>顺序有意义：菜单与用户缓存一起清时，<b>先清菜单、后清用户</b>。反过来的话，两次清之间进来的请求
	 * 会用旧菜单重建用户权限（{@code BaseUser} 的 authorities 来自菜单缓存），再被缓存 2 小时。
	 */
	public static void clear(CacheManager cacheManager, String... cacheNames) {
		List<Cache> caches = new ArrayList<>(cacheNames.length);
		for (String name : cacheNames) {
			Cache cache = cacheManager.getCache(name);
			if (cache != null) {
				caches.add(cache);
			}
		}
		runAfterCommit(() -> {
			// 逐个清、互不牵连：前一个失败不能让后一个也不清
			for (Cache cache : caches) {
				runQuietly(cache::clear, "clear " + cache.getName());
			}
		});
	}

	/**
	 * 事务提交后删一个键；没有事务同步时当场删。
	 */
	public static void evict(CacheManager cacheManager, String cacheName, Object key) {
		Cache cache = cacheManager.getCache(cacheName);
		if (cache == null) {
			return;
		}
		runAfterCommit(() -> runQuietly(() -> cache.evict(key), "evict " + cacheName + "::" + key));
	}

	/**
	 * 事务提交后删一个键，缓存按<b>指定租户</b>解析（非 {@code public:} 缓存名会落到 {@code {tenantId}:} 前缀下）；
	 * 没有事务同步时当场删。{@code tenantId} 为空时退回按当前上下文解析，与 {@link #evict} 相同。
	 */
	public static void evictForTenant(CacheManager cacheManager, String tenantId, String cacheName, Object key) {
		Cache cache = resolve(cacheManager, tenantId, cacheName);
		if (cache == null) {
			return;
		}
		runAfterCommit(() -> runQuietly(() -> cache.evict(key), "evict " + cache.getName() + "::" + key));
	}

	private static Cache resolve(CacheManager cacheManager, String tenantId, String cacheName) {
		if (tenantId == null || tenantId.isBlank()) {
			return cacheManager.getCache(cacheName);
		}
		String previous = TenantContextHolder.getTenantId();
		TenantContextHolder.setTenantId(tenantId);
		try {
			return cacheManager.getCache(cacheName);
		}
		finally {
			if (previous == null) {
				TenantContextHolder.clear();
			}
			else {
				TenantContextHolder.setTenantId(previous);
			}
		}
	}

	private static void runAfterCommit(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
			return;
		}
		action.run();
	}

	private static void runQuietly(Runnable action, String description) {
		try {
			action.run();
		} catch (RuntimeException e) {
			log.error("[缓存失效] {} 失败，相关缓存最长到 TTL 才会更新：{}", description, e.getMessage(), e);
		}
	}
}
