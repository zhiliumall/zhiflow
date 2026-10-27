package com.zhiflow.common.data.tenant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 租户过滤器配置属性
 *
 * 使用示例（application.yml）：
 * <pre>
 * zhiflow:
 *   tenant:
 *     filter:
 *       enabled: true  # 是否启用租户过滤器（默认 true）
 *       url-patterns:  # 自定义 URL 匹配模式
 *         - /api/*
 *         - /admin/*
 * </pre>
 *
 */
@Data
@ConfigurationProperties(prefix = "zhiflow.tenant.filter")
public class TenantFilterProperties {

	/**
	 * 是否启用租户过滤器
	 * 默认：true
	 */
	private boolean enabled = true;

	/**
	 * URL 匹配模式
	 * 默认：匹配所有路径 /*
	 */
	private String[] urlPatterns = {"/*"};

	/**
	 * 是否在日志中打印租户信息（用于调试）
	 * 默认：false
	 */
	private boolean logTenantInfo = false;
}
