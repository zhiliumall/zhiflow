package com.zhiflow.auth.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.auth.handler.BaseAuthenticationFailureEventHandler;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.core.util.SpringContextHolder;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.common.security.util.OAuth2EndpointUtils;
import com.zhiflow.common.security.util.OAuth2ErrorCodesExpand;
import com.zhiflow.api.upms.LogLoginApi;
import com.zhiflow.api.upms.UserApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.ConvertingCursor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.zhiflow.common.log.annotation.SysLog;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * @author
 * token
 */
@Slf4j
@RestController
@RequestMapping("/token")
public class TokenController {
	private final HttpMessageConverter<OAuth2AccessTokenResponse> accessTokenHttpResponseConverter = new OAuth2AccessTokenResponseHttpMessageConverter();

	private final AuthenticationFailureHandler authenticationFailureHandler = new BaseAuthenticationFailureEventHandler(SpringContextHolder.getBean(LogLoginApi.class));

	private final OAuth2AuthorizationService authorizationService;

	private final UserApi userApi;
	private final RedisTemplate<String, Object> redisTemplate;
	private final CacheManager cacheManager;

	public TokenController(
			OAuth2AuthorizationService authorizationService,
			@Lazy UserApi userApi,
			@Qualifier("oauth2RedisTemplate") RedisTemplate<String, Object> redisTemplate,
			CacheManager cacheManager) {
		this.authorizationService = authorizationService;
		this.userApi = userApi;
		this.redisTemplate = redisTemplate;
		this.cacheManager = cacheManager;
	}

	/**
	 * 退出并删除token
	 * @param authHeader Authorization
	 */
	@SysLog("退出登录")
	@DeleteMapping("/logout")
	public R<Boolean> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
		if (StrUtil.isBlank(authHeader)) {
			return R.ok();
		}

		String tokenValue = authHeader.replace(OAuth2AccessToken.TokenType.BEARER.getValue(), StrUtil.EMPTY).trim();
		return revokeAccessToken(tokenValue);
	}

	/**
	 * 查询 token（当前租户的在线令牌）。
	 *
	 * <p>[B5] 契约治理：入参由 {@code Map<String, Object>}（原因码 OBJECT）换成两个具名分页参数，
	 * 返回由 MyBatis 的 {@code Page}（原因码 PAGE,SDK）换成 {@link TokenPageInfo}。
	 * 两处都是<b>零行为变化</b>的：这个 Map 里除了 {@code current} / {@code size}
	 * 之外的 key 本来就没被读过；而那个 {@code Page} 从来没查过数据库 ——
	 * 数据来自 Redis SCAN，{@code new Page(current,size)} + {@code setRecords/setTotal}
	 * 纯粹是借壳，下面照着 {@code Page#getPages} 的同一算法把 {@code pages} 一并带上。
	 *
	 * @param current 页码，从 1 开始；null / &lt;1 时按 1
	 * @param size    每页条数；null / &lt;1 时按 10
	 */

	private List<String> findKeysForPage(String patternKey, int pageNum, int pageSize) {
		ScanOptions options = ScanOptions.scanOptions().match(patternKey).build();
		RedisSerializer<String> redisSerializer = (RedisSerializer<String>) redisTemplate.getKeySerializer();
		Cursor cursor = (Cursor) redisTemplate.executeWithStickyConnection(redisConnection -> new ConvertingCursor<>(redisConnection.scan(options), redisSerializer::deserialize));
		List<String> result = new ArrayList<>();
		int tmpIndex = 0;
		int startIndex = (pageNum - 1) * pageSize;
		int end = pageNum * pageSize;

		assert cursor != null;
		while (cursor.hasNext()) {
			if (tmpIndex >= startIndex && tmpIndex < end) {
				result.add(cursor.next().toString());
				tmpIndex++;
				continue;
			}
			if (tmpIndex >= end) {
				break;
			}
			tmpIndex++;
			cursor.next();
		}
		return result;
	}

	/** Revoke only an authorization owned by the currently authenticated user. */

	private List<OAuth2Authorization> allTenantAuthorizations(String tenantId) {
		ScanOptions options = ScanOptions.scanOptions().match("%s::*".formatted(CacheConstants.OAUTH_CLIENT_CACHE)).count(500).build();
		Map<String, OAuth2Authorization> result = new LinkedHashMap<>();
		try (Cursor<byte[]> cursor = redisTemplate.executeWithStickyConnection(connection -> connection.scan(options))) {
			while (cursor.hasNext()) {
				Object raw;
				try { raw = redisTemplate.opsForValue().get(new String(cursor.next(), StandardCharsets.UTF_8)); }
				catch (Exception ignored) { continue; }
				if (!(raw instanceof OAuth2Authorization authorization)) continue;
				UsernamePasswordAuthenticationToken principal = authorization.getAttribute("java.security.Principal");
				if (principal != null && principal.getPrincipal() instanceof BaseUser user && Objects.equals(tenantId, user.getTenantId())) result.putIfAbsent(authorization.getId(), authorization);
			}
		}
		return new ArrayList<>(result.values());
	}

	private String tokenValue(String authHeader) {
		if (StrUtil.isBlank(authHeader)) return StrUtil.EMPTY;
		return authHeader.replace(OAuth2AccessToken.TokenType.BEARER.getValue(), StrUtil.EMPTY).trim();
	}

	private String sessionId(OAuth2Authorization authorization) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(authorization.getId().getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (Exception exception) {
			throw new IllegalStateException("无法生成设备会话标识", exception);
		}
	}

	private BaseUser currentUser(String authHeader) {
		String token = tokenValue(authHeader);
		if (StrUtil.isBlank(token)) return null;
		OAuth2Authorization authorization = authorizationService.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
		if (authorization == null || authorization.getAccessToken() == null || authorization.getAccessToken().getToken().getExpiresAt().isBefore(Instant.now())) return null;
		UsernamePasswordAuthenticationToken principal = authorization.getAttribute("java.security.Principal");
		return principal != null && principal.getPrincipal() instanceof BaseUser user ? user : null;
	}

	/**
	 * 校验token
	 * @param token 令牌
	 */
	@SneakyThrows
	@GetMapping("/check_token")
	public void checkToken(String token, HttpServletResponse response, HttpServletRequest request) {
		ServletServerHttpResponse httpResponse = new ServletServerHttpResponse(response);

		if (StrUtil.isBlank(token)) {
			httpResponse.setStatusCode(HttpStatus.UNAUTHORIZED);
			this.authenticationFailureHandler.onAuthenticationFailure(request, response,
					new InvalidBearerTokenException(OAuth2ErrorCodesExpand.TOKEN_MISSING));
			return;
		}
		OAuth2Authorization authorization = authorizationService.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);

		// 如果令牌不存在 返回401
		if (authorization == null || authorization.getAccessToken() == null) {
			this.authenticationFailureHandler.onAuthenticationFailure(request, response,
					new InvalidBearerTokenException(OAuth2ErrorCodesExpand.INVALID_BEARER_TOKEN));
			return;
		}

		Map<String, Object> claims = authorization.getAccessToken().getClaims();
		OAuth2AccessTokenResponse sendAccessTokenResponse = OAuth2EndpointUtils.sendAccessTokenResponse(authorization,
				claims);
		this.accessTokenHttpResponseConverter.write(sendAccessTokenResponse, MediaType.APPLICATION_JSON, httpResponse);
	}

	/** 撤销一枚访问令牌：清用户缓存、删授权记录、发退出事件（供 {@link #logout} 用，不对外暴露）。 */
	private R<Boolean> revokeAccessToken(String token) {
		OAuth2Authorization authorization = authorizationService.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
		if (authorization == null) {
			return R.ok();
		}

		OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
		if (accessToken == null || StrUtil.isBlank(accessToken.getToken().getTokenValue())) {
			return R.ok();
		}
		// 清空用户信息（立即删除）
		cacheManager.getCache(CacheConstants.USER_CACHE).evictIfPresent(authorization.getPrincipalName());
		// 清空access token
		authorizationService.remove(authorization);
		// 处理自定义退出事件，保存相关日志
		SpringContextHolder.publishEvent(new LogoutSuccessEvent(new PreAuthenticatedAuthenticationToken(
				authorization.getPrincipalName(), authorization.getRegisteredClientId())));
		return R.ok();
	}
}
