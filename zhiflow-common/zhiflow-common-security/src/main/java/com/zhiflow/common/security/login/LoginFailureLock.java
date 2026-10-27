package com.zhiflow.common.security.login;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Locale;

/**
 * 后台登录失败锁定：同一账号在 {@link #window} 内连续输错 {@link #maxFailures} 次，锁 {@link #lockDuration}。
 * 锁定期间密码对了也不放行；登录成功清零计数。
 *
 * <p>账号按小写计（同一账号换大小写不能绕过）。用户名不存在与密码错误计数方式相同，不给枚举账号留差异。
 * Redis 不可用时放行：锁定是防爆破的保护，不能反过来让管理员登不上。
 */
@Slf4j
public class LoginFailureLock {

	static final String FAIL_KEY = "login:admin:fail:";

	static final String LOCK_KEY = "login:admin:lock:";

	private final StringRedisTemplate redis;

	private final int maxFailures;

	private final Duration window;

	private final Duration lockDuration;

	public LoginFailureLock(StringRedisTemplate redis, int maxFailures, Duration window, Duration lockDuration) {
		this.redis = redis;
		this.maxFailures = maxFailures;
		this.window = window;
		this.lockDuration = lockDuration;
	}

	/** @return 还剩多少秒解锁；没锁返回 0 */
	public long lockedSeconds(String username) {
		try {
			Long ttl = redis.getExpire(LOCK_KEY + normalize(username));
			return ttl == null || ttl < 0 ? 0 : ttl;
		}
		catch (RuntimeException e) {
			log.warn("登录锁定检查失败，按未锁定处理：{}", e.toString());
			return 0;
		}
	}

	/** 记一次失败；达到上限时上锁并返回 true。 */
	public boolean recordFailure(String username) {
		String user = normalize(username);
		try {
			Long n = redis.opsForValue().increment(FAIL_KEY + user);
			if (n != null && n == 1L) {
				redis.expire(FAIL_KEY + user, window);
			}
			if (n != null && n >= maxFailures) {
				redis.opsForValue().set(LOCK_KEY + user, "1", lockDuration);
				redis.delete(FAIL_KEY + user);
				log.warn("后台账号 {} 连续登录失败 {} 次，锁定 {} 分钟", user, n, lockDuration.toMinutes());
				return true;
			}
			return false;
		}
		catch (RuntimeException e) {
			log.warn("登录失败计数失败：{}", e.toString());
			return false;
		}
	}

	public void recordSuccess(String username) {
		try {
			redis.delete(FAIL_KEY + normalize(username));
		}
		catch (RuntimeException e) {
			log.warn("登录失败计数清理失败：{}", e.toString());
		}
	}

	public long lockMinutes() {
		return lockDuration.toMinutes();
	}

	static String normalize(String username) {
		return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
	}
}
