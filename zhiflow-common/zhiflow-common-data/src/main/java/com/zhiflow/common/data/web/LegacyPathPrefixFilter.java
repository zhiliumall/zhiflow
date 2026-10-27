package com.zhiflow.common.data.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * [P2.3-A] 迁移期兼容：把未加前缀的老路径改写成加了前缀的新路径。
 *
 * <h2>判据是「这条老路径对应一个真实存在的新映射」，不是黑名单</h2>
 * <p>第一版用的是「除了 actuator/swagger 之外一律改写」的黑名单。**上线当天就出事**：
 * auth 的 {@code /oauth2/token} 是 Spring Authorization Server 的<b>过滤器端点</b>，
 * 根本不是 {@code RequestMapping}，改写成 {@code /auth/oauth2/token} 后
 * 授权服务器的匹配器认不出来 → 落到登录页 302 → <b>整个登录链路断掉</b>。
 * （已紧急回滚 auth 的前缀；pay/live/weixin/upms 因为没有过滤器端点而未受影响，
 * 权限矩阵 2820 行 diff=0 佐证。）
 *
 * <p>黑名单的问题不在于漏了哪一项，而在于**它要求你预先知道所有不该改写的路径** ——
 * 过滤器端点、静态资源、第三方 starter 自带的 endpoint……这个清单永远补不全。
 * 所以改成从 {@link RequestMappingHandlerMapping} 现算：
 * 只有当 {@code 前缀 + 老路径} 能匹配上一个<b>真实注册的</b>映射时才改写，其余原样放行。
 * 这样「不该改写的」不需要枚举 —— 它们本来就没有对应的新映射。
 *
 * <h2>为什么是「改写请求」而不是 forward</h2>
 * <p>{@code RequestDispatcher.forward} 走 {@code FORWARD} 分派，Spring Security 默认不重跑，
 * 而它<b>已经</b>按老路径跑过一遍了：{@code PermitAllUrlProperties} 的白名单是从
 * handler 映射现算的、加前缀后里面全是新路径，老路径匹配不上 ⇒ 契约调用（不带 token）
 * 会被判要登录、返回 424。那不是兼容，是把契约面整个打断。
 * 改写则排在安全链之前（order=-200 vs Security 的 -100），安全链、handler 映射、
 * {@code @Inside} 校验看到的都是同一个新路径。
 */
@Slf4j
public class LegacyPathPrefixFilter extends OncePerRequestFilter {

	/** 必须早于 Spring Security（-100），否则安全链看到的还是老路径。 */
	public static final int ORDER = -200;

	private final String prefix;

	private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;

	private volatile Set<PathPattern> prefixedPatterns;

	public LegacyPathPrefixFilter(String prefix,
			ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
		this.prefix = prefix;
		this.handlerMapping = handlerMapping;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		String uri = request.getRequestURI();
		if (uri.equals(this.prefix) || uri.startsWith(this.prefix + "/")) {
			chain.doFilter(request, response);      // 本来就是新路径
			return;
		}
		String candidate = this.prefix + uri;
		if (!matchesRegisteredMapping(candidate)) {
			chain.doFilter(request, response);      // 没有对应的新映射 ⇒ 不是本服务 controller 的路径
			return;
		}
		if (log.isDebugEnabled()) {
			log.debug("[path-prefix] 老路径改写 {} -> {}", uri, candidate);
		}
		chain.doFilter(new RewrittenUri(request, candidate), response);
	}

	private boolean matchesRegisteredMapping(String candidate) {
		Set<PathPattern> patterns = this.prefixedPatterns;
		if (patterns == null) {
			synchronized (this) {
				if (this.prefixedPatterns == null) {
					this.prefixedPatterns = collectPrefixedPatterns();
				}
				patterns = this.prefixedPatterns;
			}
		}
		PathContainer path = PathContainer.parsePath(candidate);
		for (PathPattern pattern : patterns) {
			if (pattern.matches(path)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 收集所有已注册且落在本前缀之下的映射。
	 *
	 * <p>惰性求值：本过滤器排在 -200，构造得比 {@code RequestMappingHandlerMapping} 早，
	 * 启动期拿不到；而第一个请求到达时映射一定已经建好了。
	 */
	private Set<PathPattern> collectPrefixedPatterns() {
		Set<PathPattern> patterns = new LinkedHashSet<>();
		RequestMappingHandlerMapping mapping;
		try {
			mapping = this.handlerMapping.getIfAvailable();
		}
		catch (RuntimeException ex) {
			// 解析 handler 映射失败绝不能把请求变成 500 —— 这个查找发生在**每个**未加前缀的请求上，
			// 一次解析异常就是全站 500（2026-08-15 auth 就是这么被健康检查判死并回滚的）。
			// 退化成「不改写」：老路径会 404，那是可见、可定位的故障，而不是一场 500 风暴。
			log.error("[path-prefix] 解析 handler 映射失败，老路径兼容退化为不改写", ex);
			return patterns;
		}
		if (mapping == null) {
			log.warn("[path-prefix] 取不到 RequestMappingHandlerMapping，老路径兼容将全部不改写"
					+ "（宁可不改写也不能瞎改写：改错的表现是端点静默 404）");
			return patterns;
		}
		for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
			if (info.getPathPatternsCondition() == null) {
				continue;
			}
			for (PathPattern pattern : info.getPathPatternsCondition().getPatterns()) {
				String value = pattern.getPatternString();
				if (value.equals(this.prefix) || value.startsWith(this.prefix + "/")) {
					patterns.add(pattern);
				}
			}
		}
		log.info("[path-prefix] 老路径兼容就绪：{} 条映射落在 {} 之下", patterns.size(), this.prefix);
		return patterns;
	}

	/**
	 * 只改 URI 三兄弟。{@code getServletPath()} 与 {@code getPathInfo()} 也要改：
	 * Spring MVC 与 Spring Security 在不同版本、不同配置下取的不是同一个，
	 * 只改 {@code getRequestURI()} 会得到「安全链按新路径判、handler 按老路径找」
	 * 这种最难查的半生效状态。
	 */
	private static final class RewrittenUri extends HttpServletRequestWrapper {

		private final String uri;

		RewrittenUri(HttpServletRequest request, String uri) {
			super(request);
			this.uri = uri;
		}

		@Override
		public String getRequestURI() {
			return this.uri;
		}

		@Override
		public String getServletPath() {
			return this.uri;
		}

		@Override
		public String getPathInfo() {
			return null;
		}

		@Override
		public StringBuffer getRequestURL() {
			StringBuffer url = new StringBuffer();
			url.append(getScheme()).append("://").append(getServerName());
			int port = getServerPort();
			if (port > 0 && !((getScheme().equals("http") && port == 80)
					|| (getScheme().equals("https") && port == 443))) {
				url.append(':').append(port);
			}
			return url.append(this.uri);
		}
	}
}
