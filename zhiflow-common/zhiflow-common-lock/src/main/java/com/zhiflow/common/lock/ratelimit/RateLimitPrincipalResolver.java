package com.zhiflow.common.lock.ratelimit;

/**
 * {@link RateLimit.Dimension#USER} 取当前用户标识。common-lock 不依赖任何登录体系，由各端自己提供实现
 * （C 端取第三方会话用户，后台取登录管理员）；没有实现或返回 null 时按 IP 计数。
 */
@FunctionalInterface
public interface RateLimitPrincipalResolver {

	/** @return 当前请求的用户标识；未登录返回 null */
	String currentPrincipal();
}
