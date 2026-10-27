package com.zhiflow.common.security.service.impl;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.security.entity.BaseUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 压测优化 A1：{@code public:user_cache} 每次只 get 一次。
 *
 * <p>改造前 {@code cache.get(key) != null} 判空、{@code cache.get(key).get()} 取值各 get 一次 ——
 * 每个后台请求两次约 99 KB 的 GET + 两次 Jackson 反序列化；两次之间键被清，第二次拿到 null → NPE →
 * 内省器包成 401。对照：把改造前的写法贴回去跑这两条，分别得到 {@code expected 1, actual 2} 与
 * {@code NullPointerException}（结果见任务报告）。
 */
class BaseUserDetailsServiceImplCacheTest {

	private static final BaseUser ADMIN = new BaseUser("1", "0", "-1", "-1", "admin", "{noop}x", null,
			true, true, true, true, AuthorityUtils.createAuthorityList("sys:user:add"));

	@Test
	@DisplayName("按用户名：缓存命中只 get 一次")
	void usernameHitReadsCacheOnce() {
		CountingCache cache = new CountingCache();
		cache.put("admin", ADMIN);
		BaseUserDetailsServiceImpl service = new BaseUserDetailsServiceImpl(null, managerOf(cache));

		UserDetails loaded = service.loadUserByUsername("admin");

		assertSame(ADMIN, loaded);
		assertEquals(1, cache.gets.get(), "每次登录态加载只能读一次 user_cache");
	}

	@Test
	@DisplayName("第一次 get 命中之后键被清：仍返回第一次读到的用户，不再 NPE")
	void evictedBetweenGetsDoesNotNpe() {
		// 第一次 get 有值，之后都没有（模拟两次 get 之间被 upms clear / TTL 到期）
		VanishingCache cache = new VanishingCache(ADMIN);
		BaseUserDetailsServiceImpl service = new BaseUserDetailsServiceImpl(null, managerOf(cache));

		assertSame(ADMIN, service.loadUserByUsername("admin"));
	}

	private static CacheManager managerOf(Cache cache) {
		return new CacheManager() {
			@Override
			public Cache getCache(String name) {
				return CacheConstants.USER_CACHE.equals(name) ? cache : null;
			}

			@Override
			public Collection<String> getCacheNames() {
				return List.of(CacheConstants.USER_CACHE);
			}
		};
	}

	/** 数 get(key) 次数的缓存。 */
	private static final class CountingCache extends ConcurrentMapCache {

		final AtomicInteger gets = new AtomicInteger();

		CountingCache() {
			super(CacheConstants.USER_CACHE);
		}

		@Override
		public ValueWrapper get(Object key) {
			this.gets.incrementAndGet();
			return super.get(key);
		}

	}

	/** 只在第一次 get 时有值。 */
	private static final class VanishingCache extends ConcurrentMapCache {

		private final AtomicInteger gets = new AtomicInteger();

		private final Object value;

		VanishingCache(Object value) {
			super(CacheConstants.USER_CACHE);
			this.value = value;
		}

		@Override
		public ValueWrapper get(Object key) {
			return this.gets.getAndIncrement() == 0 ? new SimpleValueWrapper(this.value) : null;
		}

	}

}
