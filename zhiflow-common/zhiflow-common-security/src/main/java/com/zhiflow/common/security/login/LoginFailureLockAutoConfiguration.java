package com.zhiflow.common.security.login;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 后台登录失败锁定的共享实例：密码登录（auth）与首登改密（upms）用同一把锁、同一组阈值。
 *
 * <pre>
 * zhiflow.security.login-lock:
 *   enabled: true        # 关掉 = 不锁
 *   max-failures: 5
 *   window-minutes: 15
 *   lock-minutes: 15
 * </pre>
 */
@AutoConfiguration(afterName = "com.zhiflow.common.data.cache.RedisTemplateConfig")
@ConditionalOnProperty(name = "zhiflow.security.login-lock.enabled", havingValue = "true", matchIfMissing = true)
public class LoginFailureLockAutoConfiguration {

	@Bean
	@ConditionalOnBean(StringRedisTemplate.class)
	public LoginFailureLock loginFailureLock(StringRedisTemplate stringRedisTemplate, Environment env) {
		return new LoginFailureLock(stringRedisTemplate,
				env.getProperty("zhiflow.security.login-lock.max-failures", Integer.class, 5),
				Duration.ofMinutes(env.getProperty("zhiflow.security.login-lock.window-minutes", Long.class, 15L)),
				Duration.ofMinutes(env.getProperty("zhiflow.security.login-lock.lock-minutes", Long.class, 15L)));
	}
}
