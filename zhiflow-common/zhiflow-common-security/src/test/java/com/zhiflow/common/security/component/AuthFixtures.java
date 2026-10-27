package com.zhiflow.common.security.component;

import cn.hutool.extra.spring.SpringUtil;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.common.security.service.BaseUserDetailsService;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 内省器相关单测的共用桩：计数的授权服务 / 用户服务 / EXISTS。 */
final class AuthFixtures {

	static final List<GrantedAuthority> AUTHORITIES = AuthorityUtils.createAuthorityList("ROLE_1",
			"mall:adminnotification:query", "sys:user:add");

	private AuthFixtures() {
	}

	static BaseUser user(String username, String tenantId, String type, @Nullable List<String> tenantIds,
			boolean nonLocked) {
		return new BaseUser("id-" + username, "0", type, tenantId, username, "{bcrypt}secret",
				tenantIds, true, true, true, nonLocked, AUTHORITIES);
	}

	/** 一条「密码模式」签发的授权记录，principal 就是令牌里的用户。 */
	static OAuth2Authorization authorization(String clientId, BaseUser tokenUser) {
		RegisteredClient client = RegisteredClient.withId("rc-" + clientId)
			.clientId(clientId)
			.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
			.build();
		return OAuth2Authorization.withRegisteredClient(client)
			.principalName(tokenUser.getUsername())
			.authorizationGrantType(new AuthorizationGrantType("password"))
			.attribute(Principal.class.getName(),
					UsernamePasswordAuthenticationToken.authenticated(tokenUser, null, tokenUser.getAuthorities()))
			.build();
	}

	/** 按令牌返回授权记录，并数 findByToken 次数（= 改造前每次都要做的那次 GET + JDK 反序列化）。 */
	static final class CountingAuthorizationService implements OAuth2AuthorizationService {

		final Map<String, OAuth2Authorization> byToken = new ConcurrentHashMap<>();

		final AtomicInteger finds = new AtomicInteger();

		@Override
		public void save(OAuth2Authorization authorization) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void remove(OAuth2Authorization authorization) {
			throw new UnsupportedOperationException();
		}

		@Override
		public OAuth2Authorization findById(String id) {
			throw new UnsupportedOperationException();
		}

		@Override
		public OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
			this.finds.incrementAndGet();
			return this.byToken.get(token);
		}

	}

	/**
	 * 用户服务：每次返回一个<b>新</b>的 BaseUser（模拟 user_cache 每次 GET 都反序列化出新对象），并数调用次数。
	 */
	static final class CountingUserDetailsService implements BaseUserDetailsService {

		final AtomicInteger loads = new AtomicInteger();

		volatile Supplier<BaseUser> factory;

		/** 加载过程中顺带执行的动作（模拟「读 user_cache 的同时广播到达」）。 */
		volatile Runnable duringLoad = () -> {
		};

		CountingUserDetailsService(Supplier<BaseUser> factory) {
			this.factory = factory;
		}

		@Override
		public UserDetails loadUserByUsername(String username) {
			this.loads.incrementAndGet();
			BaseUser loaded = this.factory.get();
			this.duringLoad.run();
			return loaded;
		}

	}

	/** 内省器用 hutool SpringUtil 找 BaseUserDetailsService：给它一个只含桩的上下文。 */
	static GenericApplicationContext installUserDetailsService(BaseUserDetailsService service) {
		GenericApplicationContext context = new GenericApplicationContext();
		context.registerBean("baseUserDetailsService", BaseUserDetailsService.class, () -> service);
		context.refresh();
		SpringUtil springUtil = new SpringUtil();
		springUtil.postProcessBeanFactory(context.getBeanFactory());
		springUtil.setApplicationContext(context);
		return context;
	}

	/** 只实现 EXISTS 的 RedisTemplate：存活键集合可改，并数调用次数。 */
	static final class ExistsOnlyRedisTemplate extends RedisTemplate<String, Object> {

		final Set<String> liveKeys = ConcurrentHashMap.newKeySet();

		final AtomicInteger exists = new AtomicInteger();

		@Override
		public Boolean hasKey(String key) {
			this.exists.incrementAndGet();
			return this.liveKeys.contains(key);
		}

		void live(String token) {
			this.liveKeys.add(AuthorizationSnapshotCache.directAccessTokenKey(token));
		}

		void revoke(String token) {
			this.liveKeys.remove(AuthorizationSnapshotCache.directAccessTokenKey(token));
		}

	}

}
