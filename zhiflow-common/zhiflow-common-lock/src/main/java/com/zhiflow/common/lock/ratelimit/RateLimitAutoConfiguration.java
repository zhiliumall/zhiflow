package com.zhiflow.common.lock.ratelimit;

import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 装配 {@link RateLimitAspect}。有 {@link RedissonClient} 才装（common-lock 的 {@code RedissonAutoConfiguration} 提供）；
 * 维度要从当前请求取 IP，所以只在 Web 应用里装。
 */
@AutoConfiguration(afterName = "com.zhiflow.common.lock.config.RedissonAutoConfiguration")
@ConditionalOnBean(RedissonClient.class)
@ConditionalOnClass(name = "org.springframework.web.context.request.RequestContextHolder")
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitAutoConfiguration {

	@Bean
	public RateLimitAspect rateLimitAspect(RedissonClient redissonClient, RateLimitProperties properties,
			ObjectProvider<RateLimitPrincipalResolver> principalResolver) {
		return new RateLimitAspect(redissonClient, properties, principalResolver);
	}
}
