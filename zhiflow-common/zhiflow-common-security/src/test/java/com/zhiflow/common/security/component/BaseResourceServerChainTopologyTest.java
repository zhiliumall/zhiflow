package com.zhiflow.common.security.component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [P2.3-B] 安全链的**拓扑**：几条链、各自管哪些路径、用哪套认证。
 *
 * <h2>为什么值得为它起一个 Spring 上下文</h2>
 * <p>这一步能出的两类事故都发生在<b>启动期或请求期</b>，单元测试完全看不见：
 * <ol>
 * <li>两条链都写成「任意请求」→ {@code WebSecurityFilterChainValidator} 抛
 *     {@code UnreachableFilterChainException}，服务<b>起不来</b>；</li>
 * <li>只留一条按前缀圈范围的链 → 前缀之外的 59~60 条映射
 *     （actuator / error / v3-api-docs / webjars / WebSocket）<b>匹配不到任何链</b>，
 *     Spring Security 对这种请求是完全不过安全过滤器的 —— 鉴权无声消失。</li>
 * </ol>
 * 所以判据是「把上下文真起起来，数链、拿具体路径问归谁管」。
 */
class BaseResourceServerChainTopologyTest {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
		.withUserConfiguration(StubDependencies.class, BaseResourceServerConfiguration.class);

	@Test
	@DisplayName("没配前缀：只有一条链，管辖全部请求（改造前的形态，逐字不变）")
	void withoutPrefixThereIsExactlyOneChainCoveringEverything() {
		this.runner.run(context -> {
			assertFalse(context.getStartupFailure() != null, "上下文没起来");
			List<SecurityFilterChain> chains = chains(context.getBean(FilterChainProxy.class));
			assertEquals(1, chains.size(), "没配前缀时多注册一条「任意请求」的链会直接启动失败");
			assertTrue(chains.get(0).matches(get("/payconfig/list")));
			assertTrue(chains.get(0).matches(get("/actuator/health")));
		});
	}

	@Test
	@DisplayName("配了前缀：两条链，第一条按前缀圈范围，第二条兜住前缀之外的一切")
	void withPrefixTheScopedChainComesFirstAndFallbackCoversTheRest() {
		this.runner.withPropertyValues(SecurityScope.PATH_PREFIX_PROPERTY + "=/pay").run(context -> {
			List<SecurityFilterChain> chains = chains(context.getBean(FilterChainProxy.class));
			assertEquals(2, chains.size(), "缺了兜底链 = actuator/WebSocket 等路径不过任何安全过滤器");

			SecurityFilterChain scoped = chains.get(0);
			assertTrue(scoped.matches(get("/pay/payconfig/list")), "本服务命名空间没归第一条链管");
			assertFalse(scoped.matches(get("/actuator/health")), "第一条链的管辖范围没被前缀圈住");
			assertFalse(scoped.matches(get("/payment/x")), "/payment 不属于 /pay，却被圈了进来");

			SecurityFilterChain fallback = chains.get(1);
			assertTrue(fallback.matches(get("/actuator/health")));
			assertTrue(fallback.matches(get("/ws/im")), "WebSocket 端点必须仍有安全链兜着");
			assertTrue(fallback.matches(get("/webjars/x.js")));
		});
	}

	@Test
	@DisplayName("命名空间不是 addPathPrefix 加出来的服务（im-server）用安全链覆盖项圈范围")
	void securityPrefixOverrideScopesTheChainWithoutMvcPrefix() {
		// im-server 的 /api 写在 controller 源码里，配 zhiflow.path-prefix.value 会被
		// addPathPrefix 再加一层变成 /api/api/im/**。所以它只声明安全链这一侧。
		this.runner.withPropertyValues(SecurityScope.SECURITY_PREFIX_PROPERTY + "=/api").run(context -> {
			List<SecurityFilterChain> chains = chains(context.getBean(FilterChainProxy.class));
			assertEquals(2, chains.size());
			assertTrue(chains.get(0).matches(get("/api/im/admin/ai/knowledge/page")));
			assertFalse(chains.get(0).matches(get("/im/ws")), "WebSocket 端点不在 /api 之下");
			assertTrue(chains.get(1).matches(get("/im/ws")), "它必须由兜底链接住");
		});
	}

	@Test
	@DisplayName("两个前缀键同时存在时以安全链覆盖项为准")
	void securityPrefixOverrideWinsOverMvcPrefix() {
		this.runner
			.withPropertyValues(SecurityScope.PATH_PREFIX_PROPERTY + "=/pay",
					SecurityScope.SECURITY_PREFIX_PROPERTY + "=/api")
			.run(context -> {
				SecurityFilterChain scoped = chains(context.getBean(FilterChainProxy.class)).get(0);
				assertTrue(scoped.matches(get("/api/x")));
				assertFalse(scoped.matches(get("/pay/x")));
			});
	}

	@Test
	@DisplayName("前缀显式配成空串也只有一条链（注册条件与 hasPrefix 必须给出同一个答案）")
	void explicitlyBlankPrefixStillYieldsOneChain() {
		// @ConditionalOnProperty 判的是「有没有这个键」，空串会被判成「有」，
		// 而 SecurityScope.hasPrefix("") 是 false ⇒ 两条链都成了「任意请求」⇒ 启动直接失败。
		// 这就是这里用 @ConditionalOnExpression 判长度的原因。
		this.runner.withPropertyValues(SecurityScope.PATH_PREFIX_PROPERTY + "=").run(context -> {
			assertFalse(context.getStartupFailure() != null, "上下文没起来：注册条件与 hasPrefix 判定不一致");
			assertEquals(1, chains(context.getBean(FilterChainProxy.class)).size());
		});
	}

	// ==================================================================================
	// 单体的多命名空间
	// ==================================================================================

	/** 单体那 8 个命名空间的最小代表：一个 api-login、两个 oauth2、一个源码内前缀（im）。 */
	private static final String[] MONOLITH_NAMESPACES = {
			"zhiflow.path-prefix.namespaces[0].value=/mallapi",
			"zhiflow.path-prefix.namespaces[0].base-package=com.zhiflow.mall.api.controller",
			"zhiflow.path-prefix.namespaces[0].authentication-mode=api-login",
			"zhiflow.path-prefix.namespaces[1].value=/mall",
			"zhiflow.path-prefix.namespaces[1].base-package=com.zhiflow.mall.admin.controller",
			"zhiflow.path-prefix.namespaces[2].value=/upms",
			"zhiflow.path-prefix.namespaces[2].base-package=com.zhiflow.upms.admin.controller",
			"zhiflow.path-prefix.namespaces[3].value=/api" };

	@Test
	@DisplayName("⭐ 多进程零变化：没配 namespaces 时，单体那三条链一条都不注册")
	void singleNamespaceRegistersNoMonolithChains() {
		// 这条是「单体改动对多进程是恒等变换」的**机器判据**。它不是靠「逻辑上等价」论证的，
		// 而是靠「新代码根本不执行」—— 链数与改造前逐字相同（1 条），
		// 上面 withoutPrefixThereIsExactlyOneChain... 那条用例同时钉住了这一点。
		this.runner.run(context -> {
			assertFalse(context.getStartupFailure() != null, "上下文没起来");
			assertEquals(1, chains(context.getBean(FilterChainProxy.class)).size(),
					"没配 namespaces 却注册了单体的链 —— 多进程形态被单体改动污染了");
		});
	}

	@Test
	@DisplayName("多命名空间：三条链，api-login → oauth2 → 兜底，且顺序不能反")
	void multiNamespaceRegistersThreeChainsInOrder() {
		this.runner.withPropertyValues(MONOLITH_NAMESPACES).run(context -> {
			assertFalse(context.getStartupFailure() != null,
					"上下文没起来：多半是又出现了第二条 anyRequest 链");
			List<SecurityFilterChain> chains = chains(context.getBean(FilterChainProxy.class));
			assertEquals(3, chains.size(), "单体应当是 api-login / oauth2 / 兜底 三条链");

			SecurityFilterChain apiLogin = chains.get(0);
			assertTrue(apiLogin.matches(get("/mallapi/goodsspu/page")), "C 端命名空间没归 api-login 链");
			assertFalse(apiLogin.matches(get("/mall/goodsspu/page")),
					"api-login 链吞掉了后台命名空间 —— 那是把整个后台开成匿名可达");
			assertFalse(hasBearerTokenFilter(apiLogin), "api-login 链不该装资源服务器");

			SecurityFilterChain oauth2 = chains.get(1);
			assertTrue(oauth2.matches(get("/mall/goodsspu/page")));
			assertTrue(oauth2.matches(get("/upms/user/page")));
			assertTrue(oauth2.matches(get("/api/im/admin/ai/knowledge/page")), "源码内前缀的命名空间也要被圈住");
			assertFalse(oauth2.matches(get("/mallapi/goodsspu/page")), "C 端被 oauth2 链圈走 = 全线 401");
			assertTrue(hasBearerTokenFilter(oauth2), "oauth2 链没装 BearerTokenAuthenticationFilter = 不校验 token");

			SecurityFilterChain fallback = chains.get(2);
			assertTrue(fallback.matches(get("/actuator/health")));
			assertTrue(fallback.matches(get("/im/ws")), "WebSocket 端点必须仍有安全链兜着");
			assertTrue(fallback.matches(get("/webjars/x.js")));
		});
	}

	@Test
	@DisplayName("多命名空间：命名空间之外的一切都由兜底链接住（没有任何请求掉出全部链）")
	void multiNamespaceFallbackCoversEverythingElse() {
		this.runner.withPropertyValues(MONOLITH_NAMESPACES).run(context -> {
			FilterChainProxy proxy = context.getBean(FilterChainProxy.class);
			// Spring Security 对匹配不到任何链的请求是**完全不过安全过滤器**的。
			// 所以判据不是「兜底链匹配得上」，而是「随便挑一批路径，每一条都至少归某条链管」。
			for (String path : List.of("/actuator/prometheus", "/error", "/v3/api-docs", "/ws/im",
					"/im/ws", "/webjars/swagger-ui/index.html", "/favicon.ico", "/",
					"/mallapi/x", "/mall/x", "/upms/x", "/api/im/x", "/completely/unknown/path")) {
				assertTrue(chains(proxy).stream().anyMatch(chain -> chain.matches(get(path))),
						path + " 不归任何安全链管 —— 它会完全绕过安全过滤器");
			}
		});
	}

	@Test
	@DisplayName("多命名空间：空壳条目（只有 base-package 没有 value）不会被当成「匹配一切」")
	void blankNamespaceEntryIsSkippedNotTreatedAsMatchAll() {
		// 空 value 若被当成空前缀，jurisdiction("") 会抛 IllegalArgumentException，
		// 或更糟 —— 被当成「/」匹配一切，把兜底链之外的东西全吞了。
		this.runner
			.withPropertyValues("zhiflow.path-prefix.namespaces[0].value=/mall",
					"zhiflow.path-prefix.namespaces[0].base-package=com.zhiflow.mall.admin.controller",
					"zhiflow.path-prefix.namespaces[1].value=",
					"zhiflow.path-prefix.namespaces[1].base-package=com.zhiflow.x")
			.run(context -> {
				assertFalse(context.getStartupFailure() != null, "空壳命名空间条目让上下文起不来");
				List<SecurityFilterChain> chains = chains(context.getBean(FilterChainProxy.class));
				assertEquals(3, chains.size());
				assertFalse(chains.get(1).matches(get("/anything")),
						"空 value 被当成了「匹配一切」—— 那会吞掉兜底链该管的全部路径");
				assertTrue(chains.get(1).matches(get("/mall/x")));
			});
	}

	@Test
	@DisplayName("多命名空间：一个 api-login 都没有时，那条链空转而不是吞掉一切")
	void noApiLoginNamespaceYieldsAnEmptyChainNotAMatchAll() {
		this.runner
			.withPropertyValues("zhiflow.path-prefix.namespaces[0].value=/mall",
					"zhiflow.path-prefix.namespaces[0].base-package=com.zhiflow.mall.admin.controller")
			.run(context -> {
				List<SecurityFilterChain> chains = chains(context.getBean(FilterChainProxy.class));
				assertEquals(3, chains.size());
				assertFalse(chains.get(0).matches(get("/mall/x")),
						"没有 api-login 命名空间时那条链必须永不匹配 —— 匹配一切等于全站放行");
				assertFalse(chains.get(0).matches(get("/anything")));
				assertTrue(chains.get(1).matches(get("/mall/x")), "oauth2 链才该接住它");
			});
	}

	@Test
	@DisplayName("默认（oauth2）模式装了 Bearer token 过滤器")
	void oauth2ModeInstallsBearerTokenFilter() {
		this.runner.run(context -> {
			SecurityFilterChain chain = chains(context.getBean(FilterChainProxy.class)).get(0);
			assertTrue(hasBearerTokenFilter(chain),
					"资源服务器模式却没有 BearerTokenAuthenticationFilter —— 等于不校验 token");
		});
	}

	@Test
	@DisplayName("api-login 模式在 Security 层全放行（认证交给 MVC 层的 @ApiLogin）")
	void apiLoginModeSkipsResourceServer() {
		this.runner.withPropertyValues(SecurityScope.AUTHENTICATION_MODE_PROPERTY + "=api-login").run(context -> {
			SecurityFilterChain chain = chains(context.getBean(FilterChainProxy.class)).get(0);
			assertFalse(hasBearerTokenFilter(chain),
					"api-login 模式不该装 OAuth2 资源服务器 —— 装了就是 C 端全线 401");
		});
	}

	@Test
	@DisplayName("认证机制不认识的值一律按 oauth2 处理（漏配 / 拼错时退到更严的一边）")
	void unknownModeFallsBackToOauth2() {
		this.runner.withPropertyValues(SecurityScope.AUTHENTICATION_MODE_PROPERTY + "=apilogin").run(context -> {
			SecurityFilterChain chain = chains(context.getBean(FilterChainProxy.class)).get(0);
			assertTrue(hasBearerTokenFilter(chain), "拼错的模式值不能被当成全放行");
		});
	}

	// ==================================================================================
	// 写死的 /ai/** 白名单：多进程补一条，单体按命名空间各补一条
	//
	// 这批用例的判据不是「链匹配得上」而是**「放不放行」**——前者是管辖范围，
	// 后者才是 permitAll。写死的 /ai/** 算错时链的拓扑一点不变，只是那个端点
	// 从匿名可达变成 424，拓扑断言一条都看不见。
	// ==================================================================================

	@Test
	@DisplayName("判据自检：aiPermitAllPatterns 逐个前缀补一条并去重（空集合 = 一条都不补）")
	void aiPatternsAreComputedPerPrefix() {
		assertEquals(List.of("/ai/**"), BaseResourceServerConfiguration.aiPermitAllPatterns(List.of("")),
				"没配前缀时必须还是裸的 /ai/**（前缀改造之前的形态，逐字不变）");
		assertEquals(List.of("/mall/ai/**", "/upms/ai/**"),
				BaseResourceServerConfiguration.aiPermitAllPatterns(List.of("/mall", "upms/")),
				"前缀归一化口径要与 SecurityScope.normalizePrefix 完全一致");
		assertEquals(List.of("/mall/ai/**"),
				BaseResourceServerConfiguration.aiPermitAllPatterns(List.of("/mall", "/mall/")),
				"归一化后相同的前缀要去重");
		assertEquals(List.of(), BaseResourceServerConfiguration.aiPermitAllPatterns(List.of()),
				"一个命名空间都没有时补裸的 /ai/** 是一次纯粹的放宽");
	}

	@Test
	@DisplayName("多进程·没配前缀：裸的 /ai/** 放行（前缀改造之前的形态，逐字不变）")
	void withoutPrefixTheAiWhitelistStaysBare() {
		this.runner.run(context -> {
			FilterChainProxy proxy = context.getBean(FilterChainProxy.class);
			assertTrue(permits(proxy, "/ai/health/profile"), "改造前 /ai/** 就是裸的，这一步不该改变它");
			assertFalse(permits(proxy, "/goodsspu/page"), "对照组：白名单之外必须仍要令牌，否则本用例是假绿");
		});
	}

	@Test
	@DisplayName("⭐ 多进程·配了前缀：/ai/** 跟着本服务前缀走（mall-admin /ai/health/profile 全靠它匿名可达）")
	void singleNamespaceWhitelistsAiUnderItsOwnPrefix() {
		// 2026-08-16 实测：匿名 GET :6003/mall/ai/health/profile = 200，
		// 对照组 /mall/goodsspu/page = 424。mall-admin 的 AiHealthController 既没有
		// @Inside 也没有 @ApiLogin，data-id 的 release-urls 里也没有任何 /ai 条目 ——
		// 这一行**就是**那个端点唯一的 permitAll 来源。
		this.runner.withPropertyValues(SecurityScope.PATH_PREFIX_PROPERTY + "=/mall").run(context -> {
			FilterChainProxy proxy = context.getBean(FilterChainProxy.class);
			assertTrue(permits(proxy, "/mall/ai/health/profile"), "写死的 /ai/** 没跟上前缀 —— 该端点会静默变 424");
			assertFalse(permits(proxy, "/mall/goodsspu/page"), "对照组：白名单之外必须仍要令牌");
		});
	}

	@Test
	@DisplayName("⭐ 单体：每个 oauth2 命名空间各补一条 /<前缀>/ai/**（只补一条裸的等于一条都没补）")
	void multiNamespaceWhitelistsAiUnderEveryOauth2Namespace() {
		this.runner.withPropertyValues(MONOLITH_NAMESPACES).run(context -> {
			FilterChainProxy proxy = context.getBean(FilterChainProxy.class);
			assertTrue(permits(proxy, "/mall/ai/health/profile"),
					"单体里 /ai/** 补成裸的就是这一条挂 —— 全仓真实路径都带前缀，裸模式一条也匹配不上");
			assertTrue(permits(proxy, "/upms/ai/x"), "口径必须与「N 个进程各自算一份」逐条相同，不能只补 mall 那一条");
			assertTrue(permits(proxy, "/api/ai/x"), "源码内前缀的命名空间（im）同样要有自己那一条");
			assertFalse(permits(proxy, "/mall/goodsspu/page"), "对照组：白名单之外必须仍要令牌，否则本用例是假绿");
			assertTrue(permits(proxy, "/mallapi/ai/health/profile"), "C 端走 api-login 链，本来就全放行");
			assertFalse(permits(proxy, "/ai/health/profile"),
					"裸的 /ai/** 在单体里匹配不到任何真实端点，却给「命名空间之外的 /ai/...」白送一个 permitAll");
		});
	}

	/**
	 * 把请求真的喂进安全过滤器链，看它<b>放不放行</b>。
	 *
	 * <p>判据刻意是「下游 chain 有没有被调到」而不是「返回码等于 401/424」——
	 * 返回码取决于 {@code ResourceAuthExceptionEntryPoint} 的实现细节与 Spring Security 版本，
	 * 而「请求有没有到达应用」就是 {@code permitAll} 的定义本身。
	 */
	private static boolean permits(FilterChainProxy proxy, String uri) throws Exception {
		AtomicBoolean reachedApplication = new AtomicBoolean(false);
		proxy.doFilter(get(uri), new MockHttpServletResponse(), (req, res) -> reachedApplication.set(true));
		return reachedApplication.get();
	}

	private static boolean hasBearerTokenFilter(SecurityFilterChain chain) {
		return chain.getFilters().stream().anyMatch(BearerTokenAuthenticationFilter.class::isInstance);
	}

	private static List<SecurityFilterChain> chains(FilterChainProxy proxy) {
		return proxy.getFilterChains();
	}

	private static MockHttpServletRequest get(String uri) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.setRequestURI(uri);
		request.setServletPath(uri);
		return request;
	}

	/**
	 * 安全链需要的四个协作者。{@code PermitAllUrlProperties} 换成不去容器里捞
	 * {@code requestMappingHandlerMapping} 的版本 —— 本测试要问的是链的拓扑，
	 * 不是白名单是怎么算出来的（那条由权限矩阵测试盯着）。
	 */
	@Configuration(proxyBeanMethods = false)
	static class StubDependencies {

		@Bean
		PermitAllUrlProperties permitAllUrlProperties() {
			return new PermitAllUrlProperties() {
				@Override
				public void afterPropertiesSet() {
					setReleaseUrls(List.of("/actuator/**", "/error"));
				}
			};
		}

		@Bean
		BaseBearerTokenExtractor baseBearerTokenExtractor(PermitAllUrlProperties properties) {
			return new BaseBearerTokenExtractor(properties);
		}

		@Bean
		ResourceAuthExceptionEntryPoint resourceAuthExceptionEntryPoint() {
			StaticMessageSource messages = new StaticMessageSource();
			// 必须开。被拒的请求会走到 ExceptionTranslationFilter，它造一个
			// InsufficientAuthenticationException 交给本 entry point，而那一支要去查
			// OAuth2ResourceOwnerBaseAuthenticationProvider.tokenExpired 这条文案。
			// StaticMessageSource 查不到时抛 NoSuchMessageException，异常会顺着 doFilter 冒出来 ——
			// 于是本该「判定为拒绝」的用例变成一个看起来像门禁自己坏了的报错。
			messages.setUseCodeAsDefaultMessage(true);
			return new ResourceAuthExceptionEntryPoint(new JsonMapper(), messages);
		}

		@Bean
		OpaqueTokenIntrospector opaqueTokenIntrospector() {
			return token -> (OAuth2AuthenticatedPrincipal) null;
		}
	}
}
