package com.zhiflow.common.log.trace;

import com.zhiflow.common.core.observability.TraceIds;
import com.zhiflow.common.security.entity.BaseUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * B 端（令牌鉴权）请求把当前用户 id 放进 MDC {@code user.id}。
 *
 * <p>C 端商城用户走 {@code ThirdSessionHolder}，那边 set / clear 时已同步 MDC，这里遇到已有值就不覆盖。
 * 注册在拦截器链最后（{@code LOWEST_PRECEDENCE}）：IM 这类在自己拦截器里才把认证放进 SecurityContext 的服务，
 * 等它们跑完再取。MDC 由 {@link TraceMdcFilter} 在请求出口统一清。
 */
public class UserMdcInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		try {
			if (MDC.get(TraceIds.MDC_USER_ID) == null) {
				String userId = userIdOf(SecurityContextHolder.getContext().getAuthentication());
				if (userId != null) {
					MDC.put(TraceIds.MDC_USER_ID, userId);
				}
			}
		}
		catch (RuntimeException e) {
			// 观测代码不打断业务
		}
		return true;
	}

	static String userIdOf(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			return null;
		}
		if (authentication.getPrincipal() instanceof BaseUser user && user.getId() != null) {
			return String.valueOf(user.getId());
		}
		String name = authentication.getName();
		return name == null || name.isBlank() || name.length() > 64 ? null : name;
	}

}
