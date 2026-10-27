package com.zhiflow.common.core.captcha;

import cloud.tianai.captcha.cache.CacheStore;
import cloud.tianai.captcha.spring.autoconfiguration.CacheStoreAutoConfiguration;
import cloud.tianai.captcha.spring.store.impl.RedisCacheStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 钉死「滑块验证码的缓存存储必须是 Redis 版，不能退化成 JVM 内存版」。
 *
 * <p>退化的后果不是报错而是<b>全站登录被卡死</b>：验证码由 mall-api 生成、由网关校验，
 * 两个 JVM 各存各的内存，网关永远查不到 —— 而且一行报错都没有。
 *
 * <p>背景见 {@link CaptchaCacheStoreAutoConfiguration}：tianai 用来保证顺序的
 * {@code @AutoConfigureAfter} 指向了一个 Spring Boot 4 里已经挪走的类，顺序保证失效了。
 */
class CaptchaCacheStoreAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(RedisStubConfiguration.class)
			.withConfiguration(AutoConfigurations.of(
					CaptchaCacheStoreAutoConfiguration.class, CacheStoreAutoConfiguration.class));

	@Test
	@DisplayName("装上 tianai 自动配置后，CacheStore 必须是 Redis 实现而不是内存实现")
	void cacheStoreIsRedisBacked() {
		runner.run(context -> {
			CacheStore store = context.getBean(CacheStore.class);
			assertEquals(RedisCacheStore.class, store.getClass(),
					"CacheStore 退化成了 " + store.getClass().getName()
							+ " —— 验证码跨不了进程，网关校验会全数失败");
		});
	}

	@Test
	@DisplayName("本配置必须声明 before = CacheStoreAutoConfiguration（顺序保证的唯一来源）")
	void declaresBeforeTianaiAutoConfiguration() {
		AutoConfiguration meta = CaptchaCacheStoreAutoConfiguration.class
				.getAnnotation(AutoConfiguration.class);
		assertNotNull(meta, "缺 @AutoConfiguration");
		assertTrue(Arrays.asList(meta.before()).contains(CacheStoreAutoConfiguration.class),
				"没声明 before = CacheStoreAutoConfiguration ⇒ 谁先注册又变回靠运气了，实际="
						+ Arrays.toString(meta.before()));
	}

	@Configuration(proxyBeanMethods = false)
	static class RedisStubConfiguration {
		@Bean
		StringRedisTemplate stringRedisTemplate() {
			// 只需要 bean 存在即可，本测试不碰网络
			return new StringRedisTemplate(mock(RedisConnectionFactory.class));
		}
	}
}
