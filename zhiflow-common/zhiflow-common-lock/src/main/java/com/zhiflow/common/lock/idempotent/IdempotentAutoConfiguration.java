package com.zhiflow.common.lock.idempotent;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 幂等自动配置。
 *
 * <p>{@code idempotent.enabled=false} 可整体关掉（代码留存），是本能力的一键回滚方式。
 * 默认开启，但由于 {@code idempotent.require-key} 默认 false，
 * 在前端 axios 拦截器上线前不会拒绝任何请求——纯增量、向后兼容。
 */
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
@ConditionalOnClass(StringRedisTemplate.class)
@EnableConfigurationProperties(IdempotentProperties.class)
@ConditionalOnProperty(prefix = "idempotent", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IdempotentAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public IdempotentAspect idempotentAspect(StringRedisTemplate stringRedisTemplate,
			IdempotentProperties properties, ObjectMapper objectMapper) {
		return new IdempotentAspect(stringRedisTemplate, properties, objectMapper);
	}
}
