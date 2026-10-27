package com.zhiflow.boot.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.observation.SecurityObservationSettings;

/**
 * 关掉 Spring Security 自己的观测（2026-10-23）。
 *
 * <p>默认情况下 Spring Security 给安全过滤链里的每一个过滤器、每一次认证和鉴权都包一层 Micrometer Observation，
 * 产出 {@code spring_security_*} 指标。高并发压测下商品详情、购物车接口的火焰图里，这一层占应用 CPU 约 5～10%，
 * 而这些指标没有任何看板、告警在用，也没有接链路追踪。{@link SecurityObservationSettings#noObservations()}
 * 让 Spring Security 连包装都不做，比只关指标更省。
 *
 * <p>不受影响：请求级的 {@code http_server_requests} 指标（ServerHttpObservationFilter）、登录成功 / 失败的事件处理与日志。
 * 需要排查安全过滤链耗时时，删掉这个 bean 即恢复默认。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityObservationConfiguration {

	@Bean
	public SecurityObservationSettings securityObservationSettings() {
		return SecurityObservationSettings.noObservations();
	}

}
