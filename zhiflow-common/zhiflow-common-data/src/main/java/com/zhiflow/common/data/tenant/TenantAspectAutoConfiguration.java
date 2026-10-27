package com.zhiflow.common.data.tenant;

import com.zhiflow.common.data.tenant.aspect.TenantAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 租户切面自动配置
 *
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenantConfigProperties.class)
@ConditionalOnProperty(prefix = "base.tenant", name = "annotation-driven-enabled", havingValue = "true", matchIfMissing = true)
public class TenantAspectAutoConfiguration {

	@Bean
	public TenantAspect tenantAspect(TenantConfigProperties tenantConfigProperties) {
		return new TenantAspect(tenantConfigProperties);
	}
}
