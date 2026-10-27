package com.zhiflow.mall.api.config;

import com.zhiflow.common.lock.ratelimit.RateLimitPrincipalResolver;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import org.springframework.stereotype.Component;

/**
 * C 端 {@code @RateLimit(by = USER)} 的用户标识：第三方会话里的商城用户 id（未登录为 null，按 IP 计数）。
 */
@Component
public class ThirdSessionRateLimitPrincipal implements RateLimitPrincipalResolver {

	@Override
	public String currentPrincipal() {
		return ThirdSessionHolder.getUserId();
	}
}
