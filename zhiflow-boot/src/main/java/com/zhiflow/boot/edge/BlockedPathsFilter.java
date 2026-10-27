package com.zhiflow.boot.edge;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Set;

/**
 * 网关 {@code SecurityGlobalFilter} 恶意路径拦截 + CONNECT 拒绝的 servlet 版。
 *
 * <h2>网关侧语义（照搬的判据）</h2>
 * <p>网关的黑名单匹配发生在 {@code BaseRequestGlobalFilter} 全局 strip 1 段<b>之后</b>：
 * 外部 {@code /upms/.env} 被 strip 成 {@code /.env}，再 {@code startsWith} 黑名单 ⇒ 403。
 * 所以 servlet 版对每个请求匹配<b>两个</b>形态：原始路径、去掉第一段前缀之后的路径 ——
 * 得到的拦截集合与「网关 strip 后再匹配」逐字相同（业务端点若与黑名单相撞，
 * 它在网关形态下本来就是死的，这里不会拦掉任何今天活着的路径）。
 *
 * <h2>在 boot 里它真正承重的一条</h2>
 * <p>{@code /actuator/**} 在单体 release-urls 里是 permitAll（多进程各服务同样如此），
 * 黑名单里的 {@code /actuator/env|heapdump|threaddump} 是这批敏感端点在「permitAll +
 * exposure 配宽了」情形下的最后一道闸 —— 网关形态下这道闸在网关上，去网关后必须搬进来。
 * 其余条目（/.git、/wp-admin…）在 boot 里本会落到 401/404，搬过来是为了外部行为
 * （扫描器探测一律 403）与原网关逐字一致，顺带把日志口径也保住。
 *
 * <p>CONNECT：Tomcat 本身不实现 CONNECT，但网关显式回 405 并记 warn，保持一致。
 */
@Slf4j
public class BlockedPathsFilter implements Filter {

	/** 与网关 {@code SecurityGlobalFilter#BLOCKED_PATHS} 逐字相同。 */
	static final Set<String> BLOCKED_PATHS = Set.of(
			"/.env", "/.git", "/api.php", "/pay_index.html",
			"/wp-admin", "/wp-login.php", "/cgi-bin",
			"/.DS_Store", "/config.json", "/database.yml",
			"/.svn", "/.hg",
			"/WEB-INF", "/META-INF",
			"/actuator/env", "/actuator/heapdump", "/actuator/threaddump",
			"/phpinfo", "/admin.php", "/web.config"
	);

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse httpResponse)) {
			chain.doFilter(request, response);
			return;
		}

		if ("CONNECT".equals(http.getMethod())) {
			log.warn("Blocked CONNECT proxy tunnel attempt from {}", http.getRemoteAddr());
			httpResponse.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}

		String path = http.getRequestURI().toLowerCase();
		if (isBlocked(path) || isBlocked(stripFirstSegment(path))) {
			log.warn("Blocked malicious path: {} from {}", path, http.getRemoteAddr());
			httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
			return;
		}

		chain.doFilter(request, response);
	}

	private static boolean isBlocked(String path) {
		// 黑名单全小写，path 已 toLowerCase —— 与网关的 path.toLowerCase().startsWith 一致
		return BLOCKED_PATHS.stream().anyMatch(path::startsWith);
	}

	/**
	 * 去掉第一段命名空间前缀：{@code /upms/.env} → {@code /.env}。
	 * 对应网关 {@code BaseRequestGlobalFilter} 的全局 StripPrefix=1（黑名单匹配所见的形态）。
	 * 没有第二段时返回 {@code "/"}（与网关 strip 结果一致，不会误伤）。
	 */
	static String stripFirstSegment(String path) {
		if (path.length() <= 1) {
			return path;
		}
		int secondSlash = path.indexOf('/', 1);
		return secondSlash < 0 ? "/" : path.substring(secondSlash);
	}

}
