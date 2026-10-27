package com.zhiflow.common.data.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [P2.3-A] 路径前缀与迁移期兼容过滤器。
 *
 * <p>这两件事错了都不会在当下报错：前缀没生效 → 外部一切正常、阶段 3 才炸；
 * 过滤器改写多了 → actuator 与 swagger 静默 404；改写少了 → 契约调用 404。
 * 所以判据必须是**具体路径进、具体路径出**。
 */
class PathPrefixTest {

	/** 暴露 {@code PathMatchConfigurer} 的 protected getter，用来看「到底注册了什么」。 */
	private static final class ProbingConfigurer extends PathMatchConfigurer {

		Map<String, Predicate<Class<?>>> prefixes() {
			return getPathPrefixes();
		}
	}

	private static PathPrefixProperties properties(String prefix, String basePackage) {
		PathPrefixProperties properties = new PathPrefixProperties();
		properties.setValue(prefix);
		properties.setBasePackage(basePackage);
		return properties;
	}

	// ------------------------------------------------------------------ 前缀

	@Test
	@DisplayName("只给 base-package 下的 controller 加前缀，别的类一律不动")
	void prefixAppliesOnlyToConfiguredPackage() {
		WebMvcConfigurer configurer = new PathPrefixAutoConfiguration()
				.pathPrefixWebMvcConfigurer(properties("/pay", FakePayController.class.getPackageName()));
		ProbingConfigurer probe = new ProbingConfigurer();
		configurer.configurePathMatch(probe);

		Map<String, Predicate<Class<?>>> prefixes = probe.prefixes();
		assertNotNull(prefixes, "没有注册任何前缀 —— addPathPrefix 没被调用");
		assertEquals(1, prefixes.size());
		Predicate<Class<?>> predicate = prefixes.get("/pay");
		assertNotNull(predicate, "前缀键不是 /pay：" + prefixes.keySet());

		// 用本测试自己的包冒充「本服务的 controller 包」——判据是包前缀匹配，与包名叫什么无关
		assertTrue(predicate.test(FakePayController.class), "本服务 controller 应当加前缀");
		assertFalse(predicate.test(String.class), """
				给了包外的类也加前缀。全局加前缀会把 actuator、springdoc 这些
				不属于本服务的 handler 一起改掉，健康检查与监控随之全挂。""");
	}

	@Test
	@DisplayName("base-package 为空时不给任何类加前缀（宁可不生效，也不要全局乱加）")
	void emptyBasePackageMatchesNothing() {
		WebMvcConfigurer configurer = new PathPrefixAutoConfiguration()
				.pathPrefixWebMvcConfigurer(properties("/pay", ""));
		ProbingConfigurer probe = new ProbingConfigurer();
		configurer.configurePathMatch(probe);
		assertFalse(probe.prefixes().get("/pay").test(FakePayController.class),
				"base-package 漏配时应当一个都不匹配，而不是全都匹配");
	}

	// ------------------------------------------------------------------ 兼容过滤器

	@Test
	@DisplayName("老路径被改写成新路径，且 URI 三兄弟一起改")
	void legacyPathIsRewritten() throws Exception {
		MockFilterChain chain = filter("/payconfig/inside/1");
		var seen = (jakarta.servlet.http.HttpServletRequest) chain.getRequest();
		assertEquals("/pay/payconfig/inside/1", seen.getRequestURI());
		assertEquals("/pay/payconfig/inside/1", seen.getServletPath(), """
				只改了 getRequestURI 没改 getServletPath —— Spring MVC 与 Spring Security
				在不同版本/配置下取的不是同一个，会得到「安全链按新路径判、handler 按老路径找」
				这种最难查的半生效状态。""");
		assertNull(seen.getPathInfo());
		assertTrue(seen.getRequestURL().toString().endsWith("/pay/payconfig/inside/1"));
	}

	@Test
	@DisplayName("已经带前缀的路径原样放行（不会变成 /pay/pay/...）")
	void alreadyPrefixedPathUntouched() throws Exception {
		MockFilterChain chain = filter("/pay/payconfig/inside/1");
		assertEquals("/pay/payconfig/inside/1",
				((jakarta.servlet.http.HttpServletRequest) chain.getRequest()).getRequestURI());
	}

	@Test
	@DisplayName("与前缀同名但不是它的子路径的，不算已带前缀（/payment 仍要改写）")
	void similarButDifferentPathIsRewritten() throws Exception {
		MockFilterChain chain = filter("/payment/x");
		assertEquals("/pay/payment/x",
				((jakarta.servlet.http.HttpServletRequest) chain.getRequest()).getRequestURI(),
				"用 startsWith(prefix) 而不是 startsWith(prefix + \"/\") 会把 /payment 误判成已带前缀");
	}

	@Test
	@DisplayName("没有对应新映射的路径一律不改写（actuator / swagger / 授权服务器的过滤器端点）")
	void pathsWithoutMatchingMappingUntouched() throws Exception {
		for (String path : new String[] { "/actuator/health", "/actuator/prometheus",
				"/v3/api-docs", "/swagger-ui/index.html", "/favicon.ico", "/oauth2/token" }) {
			MockFilterChain chain = filter(path);
			assertEquals(path,
					((jakarta.servlet.http.HttpServletRequest) chain.getRequest()).getRequestURI(),
					path + " 被改写了。");
		}
	}

	@Test
	@DisplayName("⚠️ 回归：/oauth2/token 这类**过滤器端点**绝不能被改写（2026-08-15 曾据此打断登录）")
	void authorizationServerFilterEndpointIsNeverRewritten() throws Exception {
		// auth 的 /oauth2/token 由 Spring Authorization Server 的过滤器处理，不是 RequestMapping。
		// 第一版用「除 actuator/swagger 外一律改写」的黑名单，把它改写成 /auth/oauth2/token，
		// 授权服务器的匹配器认不出来 → 落到登录页 302 → 整个登录链路断掉。
		// 判据改成「前缀+老路径 能不能匹配上一个真实注册的映射」之后，这类端点自动被放过——
		// 不需要有人记得把它加进黑名单。
		MockFilterChain chain = filter("/oauth2/token");
		assertEquals("/oauth2/token",
				((jakarta.servlet.http.HttpServletRequest) chain.getRequest()).getRequestURI(),
				"过滤器端点被改写 = 登录挂掉");
	}

	@Test
	@DisplayName("过滤器必须排在 Spring Security 之前")
	void filterRunsBeforeSpringSecurity() {
		// springSecurityFilterChain 的默认 order 是 -100（SecurityProperties.DEFAULT_FILTER_ORDER）。
		// 排在它之后的话，安全链看到的还是老路径，而 permitAll 白名单是按**新**路径算出来的
		// ⇒ 契约调用（不带 token）会被判要登录、返回 424，兼容层反而把契约面打断。
		assertTrue(LegacyPathPrefixFilter.ORDER < -100,
				"过滤器 order=" + LegacyPathPrefixFilter.ORDER + "，没有排在 Spring Security(-100) 之前");
	}

	/**
	 * 造一个只注册了 {@code /pay/payconfig/**} 与 {@code /pay/payment/**} 的 handler 映射，
	 * 模拟「本服务的 controller 已经全部挂到 /pay 之下」。
	 * 判据就是拿它算出来的 —— 不在这张表里的路径一律不改写。
	 */
	@Test
	@DisplayName("⚠️ 回归：handler 映射解析失败时退化为不改写，绝不把请求变成 500")
	void resolutionFailureDegradesToNoRewrite() throws Exception {
		// 容器里其实有两个 RequestMappingHandlerMapping（MVC 的 + actuator 的
		// controllerEndpointHandlerMapping），不加 @Qualifier 时 getIfAvailable() 抛
		// NoUniqueBeanDefinitionException —— 而它是在请求线程里抛的 ⇒ 每个请求 500。
		// 2026-08-15 auth 发版就是这样被流水线健康检查判死回滚的。
		// 真正的修法是 @Qualifier；这条用例守住第二道：**无论什么原因解析失败，都不能 500**。
		ObjectProvider<RequestMappingHandlerMapping> exploding = new SingletonProvider<>(null) {
			@Override
			public RequestMappingHandlerMapping getIfAvailable() {
				throw new IllegalStateException("expected single matching bean but found 2");
			}
		};
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
		request.setRequestURI("/actuator/prometheus");
		MockFilterChain chain = new MockFilterChain();
		new LegacyPathPrefixFilter("/pay", exploding)
				.doFilter(request, new MockHttpServletResponse(), chain);
		assertEquals("/actuator/prometheus",
				((jakarta.servlet.http.HttpServletRequest) chain.getRequest()).getRequestURI(),
				"解析失败时应当原样放行");
	}

	private static MockFilterChain filter(String uri) throws Exception {
		RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping() {
			@Override
			public Map<RequestMappingInfo, HandlerMethod> getHandlerMethods() {
				Map<RequestMappingInfo, HandlerMethod> methods = new LinkedHashMap<>();
				for (String pattern : new String[] { "/pay/payconfig/**", "/pay/payment/**" }) {
					methods.put(RequestMappingInfo.paths(pattern).build(), null);
				}
				return methods;
			}
		};
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.setRequestURI(uri);
		MockFilterChain chain = new MockFilterChain();
		new LegacyPathPrefixFilter("/pay", new SingletonProvider<>(mapping))
				.doFilter(request, new MockHttpServletResponse(), chain);
		return chain;
	}

	/** {@code ObjectProvider} 的最小实现——只需要 {@code getIfAvailable()}。 */
	private static class SingletonProvider<T> implements ObjectProvider<T> {

		private final T value;

		SingletonProvider(T value) {
			this.value = value;
		}

		@Override
		public T getObject() {
			return this.value;
		}

		@Override
		public T getObject(Object... args) {
			return this.value;
		}

		@Override
		public T getIfAvailable() {
			return this.value;
		}

		@Override
		public T getIfUnique() {
			return this.value;
		}
	}

	static class FakePayController {
	}
}
