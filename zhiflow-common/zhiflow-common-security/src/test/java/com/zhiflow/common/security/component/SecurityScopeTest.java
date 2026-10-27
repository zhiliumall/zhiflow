package com.zhiflow.common.security.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [P2.3-B] 安全链管辖范围与认证机制的口径。
 *
 * <p>这两件事错了都不会在编译期或日志里报错，只会在生产上表现为
 * 「整片端点变 302」或「整个后台匿名可达」。2026-08-15 auth 那次就是前者：
 * controller 路径变成 {@code /auth/token/**} 而 matcher 还写着 {@code /token/**}，
 * 12 个端点全落进表单登录分支，登出直接失效。所以判据必须是**具体路径进、归不归本链管出**。
 */
class SecurityScopeTest {

	@Test
	@DisplayName("管辖范围同时覆盖前缀本身与其子路径")
	void jurisdictionCoversPrefixAndChildren() {
		RequestMatcher matcher = SecurityScope.jurisdiction("/pay");
		assertTrue(matcher.matches(get("/pay")), "裸前缀不归本链管，会掉到兜底链去");
		assertTrue(matcher.matches(get("/pay/payconfig/inside/1")));
		assertTrue(matcher.matches(get("/pay/payconfig/list")));
	}

	@Test
	@DisplayName("与前缀同名但不是它子路径的不归本链管（/payment 不属于 /pay）")
	void jurisdictionDoesNotLeakToSimilarPaths() {
		RequestMatcher matcher = SecurityScope.jurisdiction("/pay");
		assertFalse(matcher.matches(get("/payment/x")),
				"用 startsWith 而不是路径段匹配，会把别的服务的命名空间一起圈进来");
	}

	@Test
	@DisplayName("前缀之外的基础设施路径不归本链管——它们由兜底链接住")
	void infrastructurePathsAreOutsideJurisdiction() {
		RequestMatcher matcher = SecurityScope.jurisdiction("/pay");
		// 实测：每个已加前缀的服务，前缀之外都有 59~60 条这样的映射
		// （actuator / error / v3/api-docs / webjars / WebSocket）。
		// 它们必须由第二条链兜住，否则就是「匹配不到任何链 = 完全不过安全过滤器」。
		for (String path : new String[] { "/actuator/health", "/error", "/v3/api-docs", "/webjars/x.js", "/ws/im" }) {
			assertFalse(matcher.matches(get(path)), path + " 落进了按前缀圈定的链里");
		}
	}

	@Test
	@DisplayName("前缀写法归一：pay / /pay / /pay/ 是同一个范围")
	void prefixIsNormalized() {
		assertEquals("/pay", SecurityScope.normalizePrefix("pay"));
		assertEquals("/pay", SecurityScope.normalizePrefix("/pay/"));
		assertEquals("/pay", SecurityScope.normalizePrefix("  /pay  "));
		assertEquals("", SecurityScope.normalizePrefix(null));
		assertEquals("", SecurityScope.normalizePrefix("   "));
	}

	@Test
	@DisplayName("没配前缀时不许圈管辖范围（圈了等于把全部请求漏在安全链之外）")
	void blankPrefixRefusesToScope() {
		assertFalse(SecurityScope.hasPrefix(""));
		assertFalse(SecurityScope.hasPrefix(null));
		assertThrows(IllegalArgumentException.class, () -> SecurityScope.jurisdiction(""));
	}

	@Test
	@DisplayName("认证机制默认按更严的那一边：只有显式写 api-login 才是门户模式")
	void apiLoginModeMustBeExplicit() {
		assertTrue(SecurityScope.isApiLoginMode("api-login"));
		assertTrue(SecurityScope.isApiLoginMode(" API-LOGIN "));
		assertFalse(SecurityScope.isApiLoginMode(SecurityScope.MODE_OAUTH2));
		assertFalse(SecurityScope.isApiLoginMode(null), "取不到配置时必须落到资源服务器模式");
		assertFalse(SecurityScope.isApiLoginMode(""));
		assertFalse(SecurityScope.isApiLoginMode("apilogin"), "拼错的值不能被当成门户模式放行");
	}

	@Test
	@DisplayName("@Value 占位符的默认值是 oauth2 —— 漏配时退到更严的一边，而不是全放行")
	void placeholderDefaultsToOauth2() {
		assertEquals("${zhiflow.security.authentication-mode:oauth2}",
				SecurityScope.AUTHENTICATION_MODE_PLACEHOLDER);
	}

	@Test
	@DisplayName("管辖范围占位符：安全链覆盖项优先，退到 MVC 前缀，都没有则空")
	void prefixPlaceholderFallsBackFromOverrideToMvcPrefix() {
		assertEquals("${zhiflow.security.path-prefix:${zhiflow.path-prefix.value:}}",
				SecurityScope.PATH_PREFIX_PLACEHOLDER);
		// 兜底链的注册条件必须由同一个占位符算出来：两者给出不同答案时，
		// 要么多注册一条「任意请求」的链导致启动失败，要么少注册一条导致前缀外无安全链。
		assertEquals("'" + SecurityScope.PATH_PREFIX_PLACEHOLDER + "'.trim().length() > 0",
				SecurityScope.HAS_PREFIX_CONDITION);
	}

	/**
	 * {@code AntPathRequestMatcher} 匹配的是 {@code getServletPath() + getPathInfo()}，
	 * 不是 {@code getRequestURI()}。DispatcherServlet 映射在 {@code /} 上时容器给的
	 * servletPath 就是整条路径 —— {@code LegacyPathPrefixFilter} 的包装器也是按这个口径
	 * 把三个 URI 方法一起改的，所以这里必须照着设，否则测出来的是「什么都不匹配」的假绿。
	 */
	private static MockHttpServletRequest get(String uri) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.setRequestURI(uri);
		request.setServletPath(uri);
		return request;
	}
}
