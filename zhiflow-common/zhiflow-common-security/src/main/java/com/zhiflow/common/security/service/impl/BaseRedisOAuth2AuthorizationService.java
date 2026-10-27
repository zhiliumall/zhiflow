package com.zhiflow.common.security.service.impl;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.security.entity.BaseUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class BaseRedisOAuth2AuthorizationService implements OAuth2AuthorizationService {

	private final static Long TIMEOUT = 10L;

	private static final String AUTHORIZATION = CacheConstants.PUBLIC_CACHE + "token";

	private final RedisTemplate<String, Object> redisTemplate;

	public BaseRedisOAuth2AuthorizationService(
			@Qualifier("oauth2RedisTemplate") RedisTemplate<String, Object> oauth2RedisTemplate) {
		this.redisTemplate = oauth2RedisTemplate;
		log.info("BaseRedisOAuth2AuthorizationService 初始化成功，使用 oauth2RedisTemplate");
	}

	@Override
	public void save(OAuth2Authorization authorization) {
		Assert.notNull(authorization, "authorization cannot be null");

		if (isState(authorization)) {
			String token = authorization.getAttribute("state");
			redisTemplate.opsForValue()
				.set(buildKey(this.getTenantId(authorization), OAuth2ParameterNames.STATE, token), authorization, TIMEOUT, TimeUnit.MINUTES);
		}

		if (isCode(authorization)) {
			OAuth2Authorization.Token<OAuth2AuthorizationCode> authorizationCode = authorization
				.getToken(OAuth2AuthorizationCode.class);
			OAuth2AuthorizationCode authorizationCodeToken = authorizationCode.getToken();
			long between = ChronoUnit.MINUTES.between(authorizationCodeToken.getIssuedAt(),
					authorizationCodeToken.getExpiresAt());
			redisTemplate.opsForValue()
				.set(buildKey(this.getTenantId(authorization), OAuth2ParameterNames.CODE, authorizationCodeToken.getTokenValue()), authorization,
						between, TimeUnit.MINUTES);
		}

		if (isRefreshToken(authorization)) {
			OAuth2RefreshToken refreshToken = authorization.getRefreshToken().getToken();
			long between = ChronoUnit.SECONDS.between(refreshToken.getIssuedAt(), refreshToken.getExpiresAt());
			redisTemplate.opsForValue()
				.set(buildKey(this.getTenantId(authorization), OAuth2ParameterNames.REFRESH_TOKEN, refreshToken.getTokenValue()), authorization, between,
						TimeUnit.SECONDS);
		}

		if (isAccessToken(authorization)) {
			OAuth2AccessToken accessToken = authorization.getAccessToken().getToken();
			long between = ChronoUnit.SECONDS.between(accessToken.getIssuedAt(), accessToken.getExpiresAt());
			redisTemplate.opsForValue()
				.set(buildKey(this.getTenantId(authorization), OAuth2ParameterNames.ACCESS_TOKEN, accessToken.getTokenValue()), authorization, between,
						TimeUnit.SECONDS);
			// 直查键（无租户后缀）：access_token 每个鉴权请求都要按 token 查一次，
			// 原 findByToken 用 KEYS 通配扫全库（阻塞 valkey 单线程）。token 值全局唯一，
			// 额外写一份无后缀键，findByToken 可直接 GET，无需 KEYS。租户仍在 value 的 BaseUser 里。
			redisTemplate.opsForValue()
				.set(buildDirectKey(OAuth2ParameterNames.ACCESS_TOKEN, accessToken.getTokenValue()), authorization, between,
						TimeUnit.SECONDS);
		}
	}

	@Override
	public void remove(OAuth2Authorization authorization) {
		Assert.notNull(authorization, "authorization cannot be null");

		List<String> keys = new ArrayList<>();
		if (isState(authorization)) {
			String token = authorization.getAttribute("state");
			keys.add(buildKey(this.getTenantId(authorization), OAuth2ParameterNames.STATE, token));
		}

		if (isCode(authorization)) {
			OAuth2Authorization.Token<OAuth2AuthorizationCode> authorizationCode = authorization
				.getToken(OAuth2AuthorizationCode.class);
			OAuth2AuthorizationCode authorizationCodeToken = authorizationCode.getToken();
			keys.add(buildKey(this.getTenantId(authorization), OAuth2ParameterNames.CODE, authorizationCodeToken.getTokenValue()));
		}

		if (isRefreshToken(authorization)) {
			OAuth2RefreshToken refreshToken = authorization.getRefreshToken().getToken();
			keys.add(buildKey(this.getTenantId(authorization), OAuth2ParameterNames.REFRESH_TOKEN, refreshToken.getTokenValue()));
		}

		if (isAccessToken(authorization)) {
			OAuth2AccessToken accessToken = authorization.getAccessToken().getToken();
			keys.add(buildKey(this.getTenantId(authorization), OAuth2ParameterNames.ACCESS_TOKEN, accessToken.getTokenValue()));
			keys.add(buildDirectKey(OAuth2ParameterNames.ACCESS_TOKEN, accessToken.getTokenValue()));
		}
		redisTemplate.delete(keys);
	}

	@Override
	@Nullable
	public OAuth2Authorization findById(String id) {
		throw new UnsupportedOperationException();
	}

	@Override
	@Nullable
	public OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
		Assert.hasText(token, "token cannot be empty");
		Assert.notNull(tokenType, "tokenType cannot be empty");
		// 优先直查无租户后缀键（access_token 走这条，避免 KEYS 全库扫描）。
		String directKey = buildDirectKey(tokenType.getValue(), token);
		OAuth2Authorization direct = readTolerant(directKey);
		if (direct != null) {
			return direct;
		}
		// 回退：兼容旧格式（带租户后缀）或未写直查键的类型（code/state/refresh_token），
		// 与改动前行为完全一致；旧 token 过期后此路径自然消失。
		Set<String> keys = redisTemplate.keys(buildKey("*", tokenType.getValue(), token));
		if (keys == null || keys.isEmpty()) {
			return null;
		}
		return readTolerant(keys.iterator().next());
	}

	/**
	 * 读一条授权记录；**反序列化失败按"不存在"处理并删掉坏键**，而不是把 500 抛给调用方。
	 *
	 * <p>Redis 里存的是 JDK 序列化的 {@code OAuth2Authorization}。跨大版本升级时该类的 serialVersionUID 会变
	 * （2026-09-11 升级 Boot 4 / Authorization Server 7 时实测：旧 token 一律
	 * {@code InvalidClassException ... local class incompatible}），升级前签发的 token 全部读不出来。
	 * 这是不可避免的：旧会话必须重新登录。但对客户端的正确信号是 401（令牌无效 → 走重登），
	 * 而不是 500（客户端当成服务故障、不会重登）。坏键顺手删掉，避免每次请求都反复反序列化失败。
	 */
	@Nullable
	private OAuth2Authorization readTolerant(String key) {
		try {
			return (OAuth2Authorization) redisTemplate.opsForValue().get(key);
		}
		catch (org.springframework.data.redis.serializer.SerializationException
				| ClassCastException ex) {
			log.warn("授权记录反序列化失败，按无效令牌处理并清除: key={} cause={}", abbreviateKey(key),
					ex.getCause() != null ? ex.getCause().toString() : ex.toString());
			try {
				redisTemplate.delete(key);
			}
			catch (RuntimeException ignore) {
				// 删不掉也不影响返回 401
			}
			return null;
		}
	}

	private String buildDirectKey(String type, String id) {
		return "%s::%s::%s".formatted(AUTHORIZATION, type, id);
	}

	private String buildKey(String tenantId, String type, String id) {
		return "%s::%s::%s:%s".formatted(AUTHORIZATION, type, id, tenantId);
	}

	private static boolean isState(OAuth2Authorization authorization) {
		return Objects.nonNull(authorization.getAttribute("state"));
	}

	private static boolean isCode(OAuth2Authorization authorization) {
		OAuth2Authorization.Token<OAuth2AuthorizationCode> authorizationCode = authorization
			.getToken(OAuth2AuthorizationCode.class);
		return Objects.nonNull(authorizationCode);
	}

	private static boolean isRefreshToken(OAuth2Authorization authorization) {
		return Objects.nonNull(authorization.getRefreshToken());
	}

	private static boolean isAccessToken(OAuth2Authorization authorization) {
		return Objects.nonNull(authorization.getAccessToken());
	}

	private String getTenantId(OAuth2Authorization auth2Authorization){
		Map<String, Object> additionalInformation =  auth2Authorization.getAttributes();
		UsernamePasswordAuthenticationToken principal = (UsernamePasswordAuthenticationToken)additionalInformation.get("java.security.Principal");
		if(principal == null){
			return auth2Authorization.getRegisteredClientId();
		}
		BaseUser baseUser = (BaseUser)principal.getPrincipal();
		return baseUser.getTenantId();
	}

	/** 日志里只保留 token 前缀：这些键是失效令牌，但仍不该把完整值写进日志。 */
	private static String abbreviateKey(String key) {
		int i = key.lastIndexOf("::");
		String prefix = i >= 0 ? key.substring(0, i + 2) : "";
		String tail = i >= 0 ? key.substring(i + 2) : key;
		return prefix + (tail.length() > 12 ? tail.substring(0, 12) + "…" : tail);
	}
}
