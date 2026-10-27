package com.zhiflow.common.data.tenant;

import com.zhiflow.api.upms.UserApi;
import com.zhiflow.common.security.component.AuthorizationSnapshotCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 租户过滤器自动配置
 *
 * 架构设计：
 * - 在 Spring Security Filter Chain 之前执行，确保租户上下文在认证之前设置
 * - 使用 FilterRegistrationBean 而不是集成到 SecurityFilterChain，避免耦合
 * - 顺序设置为 SecurityFilterChain 之前（ORDER: Integer.MIN_VALUE + 50）
 *
 */
@Slf4j
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(UserApi.class)
@ConditionalOnBean(UserApi.class)
@ConditionalOnProperty(prefix = "zhiflow.tenant.filter", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TenantFilterProperties.class)
@AutoConfigureBefore(name = "com.zhiflow.common.security.component.BaseResourceServerAutoConfiguration")
public class TenantFilterConfiguration {

	/**
	 * Spring Security FilterChainProxy 默认顺序
	 * Spring Boot 3.x: SecurityFilterChain @Order(HIGHEST_PRECEDENCE) = Integer.MIN_VALUE
	 */
	private static final int SECURITY_FILTER_CHAIN_ORDER = Integer.MIN_VALUE;

	/**
	 * 租户过滤器顺序：在 Security Filter Chain 之前，但不是最高优先级
	 * 这样可以确保：
	 * 1. 租户上下文在 Security 认证之前设置
	 * 2. 不会与其他最高优先级 Filter 冲突
	 */
	private static final int TENANT_FILTER_ORDER = SECURITY_FILTER_CHAIN_ORDER + 50;

	/**
	 * 注册租户上下文过滤器
	 *
	 * 使用 FilterRegistrationBean 的优势：
	 * 1. 独立于 Spring Security Filter Chain，降低耦合
	 * 2. 精确控制执行顺序
	 * 3. 使用 ObjectProvider 优雅处理可选依赖
	 * 4. 支持动态启用/禁用和配置化
	 *
	 * @param redisTemplateProvider OAuth2 RedisTemplate 提供者（可选依赖）
	 * @param userApi 用户 API 客户端
	 * @param properties 租户过滤器配置属性
	 * @param snapshotCacheProvider 压测优化 A2 的内省缓存（只有 {@code @EnableBaseResourceServer} 的服务有；auth 没有）
	 * @return FilterRegistrationBean
	 */
	@Bean
	public FilterRegistrationBean<TenantContextHolderFilter> tenantContextHolderFilterRegistration(
			@Qualifier("oauth2RedisTemplate") ObjectProvider<RedisTemplate<String, Object>> redisTemplateProvider,
			UserApi userApi,
			TenantFilterProperties properties,
			ObjectProvider<AuthorizationSnapshotCache> snapshotCacheProvider) {

		// 获取 RedisTemplate，如果不存在则返回 null
		RedisTemplate<String, Object> redisTemplate = redisTemplateProvider.getIfAvailable();

		if (redisTemplate == null) {
			log.warn("⚠️ oauth2RedisTemplate 不存在，TenantContextHolderFilter 将被禁用");
			FilterRegistrationBean<TenantContextHolderFilter> registration = new FilterRegistrationBean<>();
			registration.setEnabled(false);
			return registration;
		}

		log.info(">>> 注册 TenantContextHolderFilter");

		TenantContextHolderFilter filter = new TenantContextHolderFilter(redisTemplate, userApi,
				snapshotCacheProvider.getIfAvailable());
		FilterRegistrationBean<TenantContextHolderFilter> registration = new FilterRegistrationBean<>(filter);

		// 设置顺序：在 Spring Security Filter Chain 之前
		registration.setOrder(TENANT_FILTER_ORDER);

		// 使用配置的 URL 模式
		registration.addUrlPatterns(properties.getUrlPatterns());

		// 设置 Filter 名称（便于调试和监控）
		registration.setName("tenantContextHolderFilter");

		log.info("✅ TenantContextHolderFilter 注册完成");
		log.info("   - 执行顺序: {} (Spring Security: {})", TENANT_FILTER_ORDER, SECURITY_FILTER_CHAIN_ORDER);
		log.info("   - URL 模式: {}", String.join(", ", properties.getUrlPatterns()));
		log.info("   - 在 Spring Security 之前执行，确保租户上下文正确设置");

		return registration;
	}
}
