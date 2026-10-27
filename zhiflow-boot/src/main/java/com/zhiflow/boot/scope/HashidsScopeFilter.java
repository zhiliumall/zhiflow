package com.zhiflow.boot.scope;

import com.zhiflow.common.core.jackson.HashidsScope;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * [P3] 把 {@code hashids.salt} 的作用范围从「进程」还原成「mall-api 命名空间」。
 *
 * <p>多进程形态下这个范围由进程边界天然保证（只有 mall-api 加载那份配置）。
 * 单体里 salt 变成进程级的，于是 B 端也开始吐 hashid —— 完整背景与两处实测后果见
 * {@link HashidsScope}。
 *
 * <p>本 filter 只做一件事：<b>当前请求属于 mall-api 命名空间 ⇒ 打开编码，否则关闭</b>。
 * 进程级默认由 {@link HashidsScopeConfiguration} 置为关，所以非请求线程
 * （定时任务、异步任务等）也与多进程一致。
 *
 * <p>⚠️ 必须 {@code finally} 清理：servlet 容器复用工作线程，不清会把上一个请求的
 * 判定带给下一个请求 —— 表现是「同一个接口时而返回 hashid 时而返回原始 id」，
 * 取决于该线程上一次伺候的是谁，这类故障极难复现。
 */
@Slf4j
public class HashidsScopeFilter implements Filter {

	/** mall-api 命名空间前缀，如 {@code /mallapi}（已归一化：以 / 开头、不以 / 结尾）。 */
	private final String portalPrefix;

	public HashidsScopeFilter(String portalPrefix) {
		this.portalPrefix = portalPrefix;
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!(request instanceof HttpServletRequest http)) {
			chain.doFilter(request, response);
			return;
		}
		HashidsScope.set(isPortalRequest(http));
		try {
			chain.doFilter(request, response);
		}
		finally {
			HashidsScope.clear();
		}
	}

	/**
	 * 前缀匹配必须带段边界，否则 {@code /mallapi} 会把 {@code /mallapiXXX} 也算进来。
	 * 反方向（{@code /mall} 误吞 {@code /mallapi}）本类不涉及——只判 mall-api 这一个前缀。
	 */
	boolean isPortalRequest(HttpServletRequest request) {
		String uri = request.getRequestURI();
		if (uri == null) {
			return false;
		}
		String contextPath = request.getContextPath();
		if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
			uri = uri.substring(contextPath.length());
		}
		return uri.equals(portalPrefix) || uri.startsWith(portalPrefix + "/");
	}

}
