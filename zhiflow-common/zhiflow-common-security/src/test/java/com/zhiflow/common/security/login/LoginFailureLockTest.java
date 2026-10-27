package com.zhiflow.common.security.login;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台登录失败锁定：连错 N 次上锁、锁定期间可查剩余时间、成功清零、大小写同一账号、Redis 出错放行。
 *
 * <p>不用 Mockito mock {@link StringRedisTemplate}（具体类，会污染 TypeCache），用内存版替身。
 */
class LoginFailureLockTest {

	private final InMemoryRedis redis = new InMemoryRedis();

	private final LoginFailureLock lock = new LoginFailureLock(redis, 3, Duration.ofMinutes(15), Duration.ofMinutes(10));

	@Test
	@DisplayName("连错 3 次：第 3 次上锁，锁定期间查得到剩余秒数，失败计数清掉")
	void locksAfterMaxFailures() {
		assertFalse(lock.recordFailure("admin"));
		assertFalse(lock.recordFailure("admin"));
		assertEquals(0, lock.lockedSeconds("admin"));

		assertTrue(lock.recordFailure("admin"));

		assertEquals(600, lock.lockedSeconds("admin"));
		assertFalse(redis.data.containsKey(LoginFailureLock.FAIL_KEY + "admin"));
	}

	@Test
	@DisplayName("登录成功清零：之前的失败不累计到下一次")
	void successResetsCounter() {
		lock.recordFailure("admin");
		lock.recordFailure("admin");
		lock.recordSuccess("admin");

		assertFalse(lock.recordFailure("admin"));
		assertFalse(lock.recordFailure("admin"));
	}

	@Test
	@DisplayName("同一账号换大小写、带空格不能绕过；不同账号互不影响")
	void normalizesUsername() {
		lock.recordFailure("Admin");
		lock.recordFailure(" ADMIN ");
		assertTrue(lock.recordFailure("admin"));

		assertEquals(0, lock.lockedSeconds("operator"));
	}

	@Test
	@DisplayName("第一次失败给计数设窗口过期时间")
	void firstFailureSetsWindow() {
		lock.recordFailure("admin");
		assertEquals(900L, redis.ttl.get(LoginFailureLock.FAIL_KEY + "admin"));
	}

	@Test
	@DisplayName("Redis 出错：不锁、不抛，管理员照常能登")
	void failsOpen() {
		redis.broken = true;
		assertFalse(lock.recordFailure("admin"));
		assertEquals(0, lock.lockedSeconds("admin"));
		lock.recordSuccess("admin");
	}

	/** 只实现 LoginFailureLock 用到的操作。 */
	private static final class InMemoryRedis extends StringRedisTemplate {

		final Map<String, String> data = new HashMap<>();

		final Map<String, Long> ttl = new HashMap<>();

		boolean broken;

		@Override
		@SuppressWarnings("unchecked")
		public ValueOperations<String, String> opsForValue() {
			return (ValueOperations<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { ValueOperations.class }, (proxy, method, args) -> {
						check();
						return switch (method.getName()) {
							case "increment" -> {
								long n = Long.parseLong(data.getOrDefault((String) args[0], "0")) + 1;
								data.put((String) args[0], Long.toString(n));
								yield n;
							}
							case "set" -> {
								data.put((String) args[0], (String) args[1]);
								ttl.put((String) args[0], ((Duration) args[2]).toSeconds());
								yield null;
							}
							default -> throw new UnsupportedOperationException(method.getName());
						};
					});
		}

		@Override
		public Boolean expire(String key, Duration timeout) {
			check();
			ttl.put(key, timeout.toSeconds());
			return true;
		}

		@Override
		public Long getExpire(String key) {
			check();
			return data.containsKey(key) ? ttl.getOrDefault(key, -1L) : -2L;
		}

		@Override
		public Boolean delete(String key) {
			check();
			ttl.remove(key);
			return data.remove(key) != null;
		}

		private void check() {
			if (broken) {
				throw new IllegalStateException("redis down");
			}
		}
	}
}
