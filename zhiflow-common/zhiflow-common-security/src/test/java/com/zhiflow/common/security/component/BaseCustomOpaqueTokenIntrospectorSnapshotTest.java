package com.zhiflow.common.security.component;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.security.component.AuthFixtures.CountingAuthorizationService;
import com.zhiflow.common.security.component.AuthFixtures.CountingUserDetailsService;
import com.zhiflow.common.security.component.AuthFixtures.ExistsOnlyRedisTemplate;
import com.zhiflow.common.security.entity.BaseUser;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 令牌内省器的压测优化：A1（同请求复用过滤器读到的授权记录）与 A2（进程内缓存，开关默认关）。
 *
 * <p>每条否定性断言都配了一个对照：同一套桩在「本应成功」的输入下确实成功，
 * 否则「没命中 / 被拒」可能只是桩坏了（仓库记忆 verify-with-a-control-sample）。
 */
class BaseCustomOpaqueTokenIntrospectorSnapshotTest {

	private static final String TOKEN = "tok-admin-a";

	private CountingAuthorizationService authorizations;

	private CountingUserDetailsService users;

	private ExistsOnlyRedisTemplate redis;

	private MockEnvironment environment;

	private FakeTicker ticker;

	private AuthorizationSnapshotCache cache;

	private BaseCustomOpaqueTokenIntrospector introspector;

	private GenericApplicationContext context;

	@BeforeEach
	void setUp() {
		this.authorizations = new CountingAuthorizationService();
		this.authorizations.byToken.put(TOKEN,
				AuthFixtures.authorization("admin-client", AuthFixtures.user("admin", "-1", "-1", null, true)));
		this.users = new CountingUserDetailsService(() -> AuthFixtures.user("admin", "-1", "-1", null, true));
		this.context = AuthFixtures.installUserDetailsService(this.users);
		this.redis = new ExistsOnlyRedisTemplate();
		this.redis.live(TOKEN);
		this.environment = new MockEnvironment();
		this.ticker = new FakeTicker();
		this.cache = new AuthorizationSnapshotCache(this.environment, this.redis, this.ticker);
		this.introspector = new BaseCustomOpaqueTokenIntrospector(this.authorizations, this.cache);
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
		this.context.close();
	}

	private void enable() {
		this.environment.setProperty(AuthorizationSnapshotCache.ENABLED_PROPERTY, "true");
	}

	private MockHttpServletRequest bindRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
		return request;
	}

	@Nested
	@DisplayName("A1：复用租户过滤器在同一请求里读到的授权记录（始终生效）")
	class A1 {

		@Test
		@DisplayName("令牌值相等：不再 findByToken")
		void reusesWhenTokenEqual() {
			MockHttpServletRequest request = bindRequest();
			PreloadedAuthorization.loaded(request, TOKEN, authorizations.byToken.get(TOKEN));

			OAuth2AuthenticatedPrincipal principal = introspector.introspect(TOKEN);

			assertEquals("admin", principal.getName());
			assertEquals(0, authorizations.finds.get(), "过滤器读过的同一个令牌不该再读一遍");
		}

		@Test
		@DisplayName("用过之后把授权记录从 request 上放掉，只留「已确认存在」")
		void releasesAuthorizationAfterUse() {
			MockHttpServletRequest request = bindRequest();
			PreloadedAuthorization.loaded(request, TOKEN, authorizations.byToken.get(TOKEN));

			introspector.introspect(TOKEN);

			PreloadedAuthorization after = PreloadedAuthorization.find(request, TOKEN);
			assertNotNull(after);
			assertEquals(false, after.isLoaded());
			assertNull(after.getAuthorization());
			assertEquals(true, after.exists());
		}

		@Test
		@DisplayName("对照：令牌值不同（例如 ?access_token= 与头里的不是同一个）⇒ 照常 findByToken")
		void doesNotReuseWhenTokenDiffers() {
			MockHttpServletRequest request = bindRequest();
			PreloadedAuthorization.loaded(request, "some-other-token", authorizations.byToken.get(TOKEN));

			introspector.introspect(TOKEN);

			assertEquals(1, authorizations.finds.get());
		}

		@Test
		@DisplayName("过滤器查过、不存在 ⇒ 直接 401，也不再查")
		void reusesNegativeResult() {
			MockHttpServletRequest request = bindRequest();
			PreloadedAuthorization.loaded(request, TOKEN, null);

			assertThrows(InvalidBearerTokenException.class, () -> introspector.introspect(TOKEN));
			assertEquals(0, authorizations.finds.get());
		}

		@Test
		@DisplayName("过滤器只做了 EXISTS（A2 命中）而内省器自己没命中 ⇒ 仍要自己读，不能把「未读」当「不存在」")
		void verifiedExistingIsNotALoadedResult() {
			MockHttpServletRequest request = bindRequest();
			PreloadedAuthorization.verifiedExisting(request, TOKEN);

			OAuth2AuthenticatedPrincipal principal = introspector.introspect(TOKEN);

			assertEquals("admin", principal.getName());
			assertEquals(1, authorizations.finds.get());
		}

	}

	@Nested
	@DisplayName("A2：进程内内省缓存")
	class A2 {

		@Test
		@DisplayName("开关关（默认）：与改造前一样，每次都读授权记录与用户、不查 EXISTS、不进缓存")
		void flagOffBehavesAsBefore() {
			OAuth2AuthenticatedPrincipal first = introspector.introspect(TOKEN);
			OAuth2AuthenticatedPrincipal second = introspector.introspect(TOKEN);

			assertEquals(2, authorizations.finds.get());
			assertEquals(2, users.loads.get());
			assertNotSame(first, second);
			assertEquals(0, redis.exists.get());
			assertEquals(0, cache.estimatedSize());
			// 开关关时不预先擦密码，交给 Spring Security 认证成功后自己擦（与改造前一致）
			assertNotNull(((BaseUser) first).getPassword());
		}

		@Test
		@DisplayName("开关开：第二次命中缓存，只查一次 EXISTS，不读授权记录、不读用户")
		void flagOnHits() {
			enable();
			OAuth2AuthenticatedPrincipal first = introspector.introspect(TOKEN);
			OAuth2AuthenticatedPrincipal second = introspector.introspect(TOKEN);

			assertSame(first, second);
			assertEquals(1, authorizations.finds.get());
			assertEquals(1, users.loads.get());
			assertEquals(1, redis.exists.get(), "命中时每请求保留一次 EXISTS");
		}

		@Test
		@DisplayName("开关现读：打开后立即生效，关掉后立即回到每次回源")
		void flagIsReadPerCall() {
			enable();
			introspector.introspect(TOKEN);
			introspector.introspect(TOKEN);
			assertEquals(1, users.loads.get());

			environment.setProperty(AuthorizationSnapshotCache.ENABLED_PROPERTY, "false");
			introspector.introspect(TOKEN);
			assertEquals(2, users.loads.get());
		}

		@Test
		@DisplayName("EXISTS 为假（登出 / 踢人 / 过期）⇒ 下一次请求就 401，条目作废")
		void revokedTokenIsRejectedNextRequest() {
			enable();
			introspector.introspect(TOKEN);
			// 对照：令牌还在时确实命中
			introspector.introspect(TOKEN);
			assertEquals(1, users.loads.get());

			// 登出：直查键被删（授权服务同时也查不到了）
			redis.revoke(TOKEN);
			authorizations.byToken.remove(TOKEN);

			assertThrows(InvalidBearerTokenException.class, () -> introspector.introspect(TOKEN));
			assertEquals(0, cache.estimatedSize());
		}

		@Test
		@DisplayName("过滤器已确认存在（verifiedExisting）⇒ 内省器命中时不再重复 EXISTS")
		void reusesFilterExistenceCheck() {
			enable();
			introspector.introspect(TOKEN);
			MockHttpServletRequest request = bindRequest();
			PreloadedAuthorization.verifiedExisting(request, TOKEN);

			introspector.introspect(TOKEN);

			assertEquals(0, redis.exists.get());
			assertEquals(1, users.loads.get());
		}

		@Test
		@DisplayName("失效广播（user_cache 被清）⇒ 条目作废，下一次重新加载")
		void userCacheBroadcastInvalidates() {
			enable();
			introspector.introspect(TOKEN);
			AuthorizationSnapshotInvalidationListener listener = new AuthorizationSnapshotInvalidationListener(cache);

			// 对照：同一频道上别的业务缓存的消息不影响
			listener.onMessage(message("\"mall:activity:seckill:1\""), null);
			introspector.introspect(TOKEN);
			assertEquals(1, users.loads.get());

			// 发布端写的是 JSON 字符串（带引号）
			listener.onMessage(message("\"public:user_cache::admin\""), null);
			assertEquals(0, cache.estimatedSize());
			introspector.introspect(TOKEN);
			assertEquals(2, users.loads.get());
		}

		@Test
		@DisplayName("令牌缓存被清（删角色全员登出）的广播同样作废")
		void tokenCacheBroadcastInvalidates() {
			enable();
			introspector.introspect(TOKEN);
			new AuthorizationSnapshotInvalidationListener(cache)
				.onMessage(message("\"public:token::access_token::*\""), null);
			assertEquals(0, cache.estimatedSize());
		}

		@Test
		@DisplayName("（重新）订阅成功 ⇒ 整体作废（断线期间可能丢了消息）")
		void resubscribeInvalidatesAll() {
			enable();
			introspector.introspect(TOKEN);
			assertEquals(1, cache.estimatedSize());

			new AuthorizationSnapshotInvalidationListener(cache)
				.onChannelSubscribed(AuthorizationSnapshotCache.INVALIDATE_CHANNEL.getBytes(StandardCharsets.UTF_8), 1);

			assertEquals(0, cache.estimatedSize());
		}

		@Test
		@DisplayName("加载过程中广播到达 ⇒ 这次加载出的旧值不留在缓存里")
		void broadcastDuringLoadIsNotLost() {
			enable();
			users.duringLoad = cache::invalidateAll;

			introspector.introspect(TOKEN);

			assertEquals(0, cache.estimatedSize(), "加载期间发生过整体作废，这条不能留");
			// 对照：没有广播时同样的加载会留下
			users.duringLoad = () -> {
			};
			introspector.introspect(TOKEN);
			assertEquals(1, cache.estimatedSize());
		}

		@Test
		@DisplayName("TTL 10 秒：过期后回源")
		void expiresAfterTtl() {
			enable();
			introspector.introspect(TOKEN);
			ticker.advance(9, TimeUnit.SECONDS);
			introspector.introspect(TOKEN);
			assertEquals(1, users.loads.get());

			ticker.advance(2, TimeUnit.SECONDS);
			introspector.introspect(TOKEN);
			assertEquals(2, users.loads.get());
		}

		@Test
		@DisplayName("缓存的 principal 只读：放入前已擦密码、put 好 clientId；之后每个请求都不改它")
		void cachedPrincipalIsNotMutatedPerRequest() {
			enable();
			BaseUser first = (BaseUser) introspector.introspect(TOKEN);
			assertNull(first.getPassword(), "放入前密码已置空");
			Map<String, Object> attributesSnapshot = Map.copyOf(first.getAttributes());
			assertEquals(Map.of(SecurityConstants.CLIENT_ID, "rc-admin-client"), attributesSnapshot);

			for (int i = 0; i < 3; i++) {
				BaseUser again = (BaseUser) introspector.introspect(TOKEN);
				// Spring Security 认证成功后对 principal 做的事：null → null，无害
				again.eraseCredentials();
			}

			BaseUser after = (BaseUser) introspector.introspect(TOKEN);
			assertSame(first, after);
			assertNull(after.getPassword());
			assertEquals(attributesSnapshot, Map.copyOf(after.getAttributes()));
			assertEquals(AuthFixtures.AUTHORITIES.size(), after.getAuthorities().size());
		}

		@Test
		@DisplayName("同一用户、不同客户端的两个令牌：各缓存各的，clientId 不串")
		void sameUserDifferentClientsDoNotShareEntries() {
			enable();
			String otherToken = "tok-admin-b";
			authorizations.byToken.put(otherToken,
					AuthFixtures.authorization("app-client", AuthFixtures.user("admin", "-1", "-1", null, true)));
			redis.live(otherToken);

			BaseUser viaAdmin = (BaseUser) introspector.introspect(TOKEN);
			BaseUser viaApp = (BaseUser) introspector.introspect(otherToken);

			assertNotSame(viaAdmin, viaApp);
			assertEquals("rc-admin-client", viaAdmin.getAttributes().get(SecurityConstants.CLIENT_ID));
			assertEquals("rc-app-client", viaApp.getAttributes().get(SecurityConstants.CLIENT_ID));
			assertEquals("rc-admin-client",
					((BaseUser) introspector.introspect(TOKEN)).getAttributes().get(SecurityConstants.CLIENT_ID));
		}

		@Test
		@DisplayName("被锁定的用户不进缓存（与 user_cache「未锁定才写」一致）")
		void lockedUserIsNotCached() {
			enable();
			users.factory = () -> AuthFixtures.user("admin", "-1", "-1", null, false);

			introspector.introspect(TOKEN);
			introspector.introspect(TOKEN);

			assertEquals(2, users.loads.get());
			assertEquals(0, cache.estimatedSize());
		}

		@Test
		@DisplayName("缓存里记下令牌那一半（租户三元组）供过滤器用")
		void remembersTokenTenant() {
			enable();
			authorizations.byToken.put(TOKEN, AuthFixtures.authorization("admin-client",
					AuthFixtures.user("admin", "-1", "-1", List.of("1", "2"), true)));

			introspector.introspect(TOKEN);

			AuthorizationSnapshotCache.Snapshot snapshot = cache
				.getIfAlive(AuthorizationSnapshotCache.keyOf(TOKEN), TOKEN, Boolean.TRUE);
			assertNotNull(snapshot);
			assertEquals(new TokenTenant("-1", "-1", List.of("1", "2")), snapshot.tokenTenant());
		}

	}

	private static DefaultMessage message(String body) {
		return new DefaultMessage(AuthorizationSnapshotCache.INVALIDATE_CHANNEL.getBytes(StandardCharsets.UTF_8),
				body.getBytes(StandardCharsets.UTF_8));
	}

	/** 可手动拨的时钟。 */
	private static final class FakeTicker implements Ticker {

		private final AtomicLong nanos = new AtomicLong();

		@Override
		public long read() {
			return this.nanos.get();
		}

		void advance(long amount, TimeUnit unit) {
			this.nanos.addAndGet(unit.toNanos(amount));
		}

	}

}
