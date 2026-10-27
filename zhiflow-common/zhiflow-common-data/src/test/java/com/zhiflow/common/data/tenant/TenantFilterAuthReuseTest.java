package com.zhiflow.common.data.tenant;

import com.zhiflow.common.core.constant.CommonConstants;
import cn.hutool.extra.spring.SpringUtil;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.security.component.AuthorizationSnapshotCache;
import com.zhiflow.common.security.component.BaseCustomOpaqueTokenIntrospector;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.common.security.service.BaseUserDetailsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.lang.Nullable;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 租户过滤器 + 令牌内省器串起来跑（同一个请求、同一个 Redis 桩）：
 * <ul>
 * <li>A1：授权记录每请求只 GET 一次（过滤器读，内省器复用）；租户解析结果与改造前一致；</li>
 * <li>A2：开关开且命中时只剩一次 EXISTS；令牌被删后下一次请求就退回原逻辑。</li>
 * <li>开源分支：不读 {@code switch-tenant-id} / {@code tenant-id} 头，令牌定租户，其余落默认租户 1。</li>
 * </ul>
 */
class TenantFilterAuthReuseTest {

	private static final String SUPER_TOKEN = "tok-super";

	private static final String TENANT_TOKEN = "tok-tenant";

	private final StubRedis redis = new StubRedis();

	private final AtomicInteger finds = new AtomicInteger();

	private final AtomicInteger userLoads = new AtomicInteger();

	private MockEnvironment environment;

	private AuthorizationSnapshotCache snapshots;

	private TenantContextHolderFilter filter;

	private BaseCustomOpaqueTokenIntrospector introspector;

	private GenericApplicationContext context;

	@BeforeEach
	void setUp() {
		// 超管：租户 -1、类型 -1、绑定了 1 和 2 两个租户
		this.redis.put(SUPER_TOKEN, authorization(user("root", "-1", "-1", List.of("1", "2"))));
		this.redis.put(TENANT_TOKEN, authorization(user("boss", "1", "1", null)));

		OAuth2AuthorizationService authorizations = new OAuth2AuthorizationService() {
			@Override
			public void save(OAuth2Authorization authorization) {
			}

			@Override
			public void remove(OAuth2Authorization authorization) {
			}

			@Override
			public OAuth2Authorization findById(String id) {
				return null;
			}

			@Override
			public OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
				finds.incrementAndGet();
				return (OAuth2Authorization) redis.store.get(CacheConstants.OAUTH_CLIENT_CACHE + "::" + token);
			}
		};
		BaseUserDetailsService users = new BaseUserDetailsService() {
			@Override
			public UserDetails loadUserByUsername(String username) {
				userLoads.incrementAndGet();
				return "root".equals(username) ? user("root", "-1", "-1", List.of("1", "2"))
						: user("boss", "1", "1", null);
			}

		};
		this.context = new GenericApplicationContext();
		this.context.registerBean("baseUserDetailsService", BaseUserDetailsService.class, () -> users);
		this.context.refresh();
		SpringUtil springUtil = new SpringUtil();
		springUtil.postProcessBeanFactory(this.context.getBeanFactory());
		springUtil.setApplicationContext(this.context);

		this.environment = new MockEnvironment();
		this.snapshots = new AuthorizationSnapshotCache(this.environment, this.redis);
		this.filter = new TenantContextHolderFilter(this.redis, null, this.snapshots);
		this.introspector = new BaseCustomOpaqueTokenIntrospector(authorizations, this.snapshots);
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
		TenantContextHolder.clear();
		this.context.close();
	}

	/** 跑一次「过滤器 → 链里内省」，返回链里看到的租户；内省结果放进 principalOut。 */
	private String request(String token, @Nullable String switchTenant, @Nullable String tenantHeader,
			AtomicReference<Object> principalOut) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer " + token);
		if (switchTenant != null) {
			request.addHeader("switch-tenant-id", switchTenant);
		}
		if (tenantHeader != null) {
			request.addHeader("tenant-id", tenantHeader);
		}
		AtomicReference<String> seenTenant = new AtomicReference<>();
		this.filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
			seenTenant.set(TenantContextHolder.getTenantId());
			// 生产里 RequestContextFilter（order -105）先于安全链（-100）绑定当前请求
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
			try {
				principalOut.set(this.introspector.introspect(token));
			}
			catch (InvalidBearerTokenException ex) {
				principalOut.set(ex);
			}
			finally {
				RequestContextHolder.resetRequestAttributes();
			}
		});
		return seenTenant.get();
	}

	@Test
	@DisplayName("A1：一个请求只 GET 一次授权记录；租户、principal 与改造前一致")
	void a1SingleReadPerRequest() throws Exception {
		AtomicReference<Object> principal = new AtomicReference<>();

		String tenant = request(TENANT_TOKEN, null, null, principal);

		assertEquals("1", tenant);
		assertEquals("boss", ((OAuth2AuthenticatedPrincipal) principal.get()).getName());
		assertEquals(1, this.redis.gets.get(), "过滤器读一次");
		assertEquals(0, this.finds.get(), "内省器复用，不再读第二次");
	}

	@Test
	@DisplayName("switch-tenant-id 头不再生效 —— 超管始终是系统租户 -1，租户管理员始终是 1")
	void switchTenantHeaderIgnored() throws Exception {
		AtomicReference<Object> principal = new AtomicReference<>();
		assertEquals("-1", request(SUPER_TOKEN, "2", null, principal));
		assertEquals("-1", request(SUPER_TOKEN, "999", null, principal));
		assertEquals("-1", request(SUPER_TOKEN, null, null, principal));
		// 对照：非超管带 switch-tenant-id 也不切
		assertEquals("1", request(TENANT_TOKEN, "2", null, principal));
	}

	@Test
	@DisplayName("对照：A2 开关关（默认）时就是 A1 —— 每请求 1 次 GET、1 次读用户、0 次 EXISTS")
	void a2FlagOffIsExactlyA1() throws Exception {
		AtomicReference<Object> principal = new AtomicReference<>();
		for (int i = 0; i < 3; i++) {
			request(SUPER_TOKEN, "2", null, principal);
		}
		assertEquals(3, this.redis.gets.get());
		assertEquals(0, this.finds.get());
		assertEquals(3, this.userLoads.get());
		assertEquals(0, this.redis.exists.get());
	}

	@Test
	@DisplayName("A2 命中：过滤器与内省器都不读授权记录，只一次 EXISTS；租户始终取令牌里的值")
	void a2HitUsesTokenTenant() throws Exception {
		this.environment.setProperty(AuthorizationSnapshotCache.ENABLED_PROPERTY, "true");
		AtomicReference<Object> principal = new AtomicReference<>();

		// 第一次：未命中，按 A1 路径加载并放入缓存
		assertEquals("-1", request(SUPER_TOKEN, "1", null, principal));
		assertEquals(1, this.redis.gets.get());
		assertEquals(1, this.userLoads.get());
		assertEquals(0, this.redis.exists.get());

		// 之后：同一令牌、不同的切租户头（一律不生效）
		assertEquals("-1", request(SUPER_TOKEN, "2", null, principal));
		assertEquals("-1", request(SUPER_TOKEN, "999", null, principal));
		assertEquals("-1", request(SUPER_TOKEN, null, null, principal));

		assertEquals(1, this.redis.gets.get(), "命中后过滤器不再 GET");
		assertEquals(0, this.finds.get(), "内省器也不读");
		assertEquals(1, this.userLoads.get(), "也不读 user_cache");
		assertEquals(3, this.redis.exists.get(), "每请求一次 EXISTS（由过滤器做，内省器复用）");
		assertEquals("root", ((OAuth2AuthenticatedPrincipal) principal.get()).getName());
	}

	@Test
	@DisplayName("A2：令牌被删（登出）⇒ 下一次请求退回原逻辑：租户落默认租户（不听 tenant-id 头）、内省 401")
	void a2RevokedTokenFallsBackImmediately() throws Exception {
		this.environment.setProperty(AuthorizationSnapshotCache.ENABLED_PROPERTY, "true");
		AtomicReference<Object> principal = new AtomicReference<>();
		request(TENANT_TOKEN, null, null, principal);
		request(TENANT_TOKEN, null, null, principal);
		// 对照：令牌还在时命中
		assertEquals(1, this.userLoads.get());
		assertNotNull(principal.get());

		this.redis.store.remove(CacheConstants.OAUTH_CLIENT_CACHE + "::" + TENANT_TOKEN);

		String tenant = request(TENANT_TOKEN, null, "7", principal);
		assertEquals(CommonConstants.DEFAULT_TENANT_ID, tenant, "令牌无效时落默认租户，tenant-id 头不参与");
		assertInstanceOf(InvalidBearerTokenException.class, principal.get());
		assertEquals(0, this.finds.get(), "内省器复用过滤器查到的「不存在」");
	}

	// ------------------------------------------------ 2026-09-30 跨租户越权：令牌识别与解析器一致

	/** 只跑过滤器，返回链里看到的租户。 */
	private String tenantSeen(MockHttpServletRequest request) throws Exception {
		AtomicReference<String> seen = new AtomicReference<>();
		this.filter.doFilter(request, new MockHttpServletResponse(),
				(req, res) -> seen.set(TenantContextHolder.getTenantId()));
		return seen.get();
	}

	private static MockHttpServletRequest req(String method, @Nullable String authorization,
			@Nullable String tokenParam, @Nullable String tenantHeader) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/goodsspu/page");
		if (authorization != null) {
			request.addHeader("Authorization", authorization);
		}
		if (tokenParam != null) {
			request.addParameter("access_token", tokenParam);
		}
		if (tenantHeader != null) {
			request.addHeader("tenant-id", tenantHeader);
		}
		return request;
	}

	@Test
	@DisplayName("越权修复：令牌头的任意大小写写法都按令牌定租户，伪造的 tenant-id 头不生效")
	void anyBearerCasingUsesTokenTenant() throws Exception {
		// 对照：规范写法本来就按令牌
		assertEquals("1", tenantSeen(req("GET", "Bearer " + TENANT_TOKEN, null, "2")));
		for (String scheme : List.of("bearer", "BEARER", "bEaReR")) {
			for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
				assertEquals("1", tenantSeen(req(method, scheme + " " + TENANT_TOKEN, null, "2")),
						scheme + " / " + method);
			}
		}
	}

	@Test
	@DisplayName("越权修复：GET 网址参数里的令牌按令牌定租户，伪造的 tenant-id 头不生效")
	void queryParamTokenUsesTokenTenant() throws Exception {
		assertEquals("1", tenantSeen(req("GET", null, TENANT_TOKEN, "2")));
		// 超管经网址参数：不切（没有 switch-tenant-id）⇒ 仍是 -1，而不是头里的 2
		assertEquals("-1", tenantSeen(req("GET", null, SUPER_TOKEN, "2")));
	}

	@Test
	@DisplayName("超管带 switch-tenant-id（任意令牌写法）都不切，始终是系统租户 -1")
	void superAdminSwitchIgnoredAcrossForms() throws Exception {
		MockHttpServletRequest lower = req("GET", "bearer " + SUPER_TOKEN, null, null);
		lower.addHeader("switch-tenant-id", "2");
		assertEquals("-1", tenantSeen(lower));
		MockHttpServletRequest unbound = req("GET", "bearer " + SUPER_TOKEN, null, null);
		unbound.addHeader("switch-tenant-id", "999");
		assertEquals("-1", tenantSeen(unbound));
	}

	@Test
	@DisplayName("没有令牌、令牌失效、非 GET 的网址参数 ⇒ 落默认租户，伪造的 tenant-id 头不生效")
	void noUsableTokenUsesDefaultTenant() throws Exception {
		String d = CommonConstants.DEFAULT_TENANT_ID;
		assertEquals(d, tenantSeen(req("GET", null, null, "7")), "匿名");
		assertEquals(d, tenantSeen(req("GET", "bearer no-such-token", null, "7")), "失效令牌：内省会 401");
		assertEquals(d, tenantSeen(req("POST", null, TENANT_TOKEN, "7")), "POST 网址参数解析器也不认");
		assertEquals(d, tenantSeen(req("GET", "Basic dXNlcjpwYXNz", null, "7")), "非 bearer 方案");
	}

	@Test
	@DisplayName("令牌识别规则与 BaseBearerTokenExtractor 同口径")
	void resolveTokenValueMatchesExtractorRules() {
		assertEquals(TENANT_TOKEN, TenantContextHolderFilter.resolveTokenValue(req("GET", "Bearer " + TENANT_TOKEN, null, null)));
		assertEquals(TENANT_TOKEN, TenantContextHolderFilter.resolveTokenValue(req("PUT", "bearer " + TENANT_TOKEN, null, null)));
		assertEquals(TENANT_TOKEN, TenantContextHolderFilter.resolveTokenValue(req("GET", null, TENANT_TOKEN, null)));
		assertEquals(null, TenantContextHolderFilter.resolveTokenValue(req("POST", null, TENANT_TOKEN, null)));
		assertEquals(null, TenantContextHolderFilter.resolveTokenValue(req("GET", "Bearer   ", null, null)));
		assertEquals(null, TenantContextHolderFilter.resolveTokenValue(req("GET", null, null, null)));
	}

	// ------------------------------------------------------------------ 桩

	private static BaseUser user(String username, String tenantId, String type, @Nullable List<String> tenantIds) {
		return new BaseUser("id-" + username, "0", type, tenantId, username, "{bcrypt}x", tenantIds,
				true, true, true, true, AuthorityUtils.createAuthorityList("ROLE_1", "sys:user:add"));
	}

	private static OAuth2Authorization authorization(BaseUser tokenUser) {
		RegisteredClient client = RegisteredClient.withId("rc-admin")
			.clientId("admin")
			.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
			.build();
		return OAuth2Authorization.withRegisteredClient(client)
			.principalName(tokenUser.getUsername())
			.authorizationGrantType(new AuthorizationGrantType("password"))
			.attribute(Principal.class.getName(),
					UsernamePasswordAuthenticationToken.authenticated(tokenUser, null, tokenUser.getAuthorities()))
			.build();
	}

	/** oauth2RedisTemplate 桩：GET / KEYS / EXISTS，数 GET 与 EXISTS 次数。 */
	private static final class StubRedis extends RedisTemplate<String, Object> {

		final Map<String, Object> store = new ConcurrentHashMap<>();

		final AtomicInteger gets = new AtomicInteger();

		final AtomicInteger exists = new AtomicInteger();

		@SuppressWarnings("unchecked")
		private final ValueOperations<String, Object> ops = (ValueOperations<String, Object>) Proxy.newProxyInstance(
				ValueOperations.class.getClassLoader(), new Class<?>[] { ValueOperations.class },
				(proxy, method, args) -> {
					if ("get".equals(method.getName()) && args != null && args.length == 1) {
						this.gets.incrementAndGet();
						return this.store.get((String) args[0]);
					}
					throw new UnsupportedOperationException(method.getName());
				});

		void put(String token, OAuth2Authorization authorization) {
			this.store.put(CacheConstants.OAUTH_CLIENT_CACHE + "::" + token, authorization);
		}

		@Override
		public ValueOperations<String, Object> opsForValue() {
			return this.ops;
		}

		@Override
		public Set<String> keys(String pattern) {
			return Set.of();
		}

		@Override
		public Boolean hasKey(String key) {
			this.exists.incrementAndGet();
			return this.store.containsKey(key);
		}

	}


	/** 开关值写错（如 "ture"）时按关处理，不能让过滤器抛异常、每个 B 端请求 500（2026-09-30 安全审查发现）。 */
	@Test
	void malformedFlagValueIsTreatedAsOffInsteadOfThrowing() {
		this.environment.setProperty(AuthorizationSnapshotCache.ENABLED_PROPERTY, "ture");
		org.assertj.core.api.Assertions.assertThat(this.snapshots.isEnabled()).isFalse();
		// 对照：正确写法仍然生效
		this.environment.setProperty(AuthorizationSnapshotCache.ENABLED_PROPERTY, "true");
		org.assertj.core.api.Assertions.assertThat(this.snapshots.isEnabled()).isTrue();
	}
}
