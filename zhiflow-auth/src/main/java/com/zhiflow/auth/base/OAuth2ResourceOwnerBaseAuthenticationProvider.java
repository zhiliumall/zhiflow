package com.zhiflow.auth.base;

import cn.hutool.extra.spring.SpringUtil;
import com.zhiflow.api.upms.UserApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.oauth2.GrantTypes;
import com.zhiflow.upms.common.dto.TotpLoginRequest;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.common.security.util.OAuth2ErrorCodesExpand;
import com.zhiflow.common.security.util.ScopeException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.security.Principal;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * @author 辰
 * @date 2023年10月27日09:58:16
 *
 * 处理自定义授权
 */
public abstract class OAuth2ResourceOwnerBaseAuthenticationProvider<T extends OAuth2ResourceOwnerBaseAuthenticationToken>
		implements AuthenticationProvider {

	private static final Logger LOGGER = LogManager.getLogger(OAuth2ResourceOwnerBaseAuthenticationProvider.class);

	private static final String ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1";

	private final OAuth2AuthorizationService authorizationService;

	private final OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator;

	private final AuthenticationManager authenticationManager;
	private final UserApi userApi;

	private final MessageSourceAccessor messages;

	@Deprecated
	private Supplier<String> refreshTokenGenerator;

	/**
	 * Constructs an {@code OAuth2AuthorizationCodeAuthenticationProvider} using the
	 * provided parameters.
	 * @param authorizationService the authorization service
	 * @param tokenGenerator the token generator
	 * @since 0.2.3
	 */
	public OAuth2ResourceOwnerBaseAuthenticationProvider(AuthenticationManager authenticationManager,
			OAuth2AuthorizationService authorizationService,
			OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator, UserApi userApi) {
		Assert.notNull(authorizationService, "authorizationService cannot be null");
		Assert.notNull(tokenGenerator, "tokenGenerator cannot be null");
		this.authenticationManager = authenticationManager;
		this.userApi = userApi;
		this.authorizationService = authorizationService;
		this.tokenGenerator = tokenGenerator;

		// 国际化配置
		this.messages = new MessageSourceAccessor(SpringUtil.getBean("securityMessageSource"), Locale.CHINA);
	}

	@Deprecated
	public void setRefreshTokenGenerator(Supplier<String> refreshTokenGenerator) {
		Assert.notNull(refreshTokenGenerator, "refreshTokenGenerator cannot be null");
		this.refreshTokenGenerator = refreshTokenGenerator;
	}

	public abstract UsernamePasswordAuthenticationToken buildToken(Map<String, Object> reqParameters);

	/**
	 * 当前provider是否支持此令牌类型
	 * @param authentication
	 * @return
	 */
	@Override
	public abstract boolean supports(Class<?> authentication);

	/**
	 * 当前的请求客户端是否支持此模式
	 * @param registeredClient
	 */
	public abstract void checkClient(RegisteredClient registeredClient);

	/**
	 * Performs authentication with the same contract as
	 * {@link AuthenticationManager#authenticate(Authentication)} .
	 * @param authentication the authentication request object.
	 * @return a fully authenticated object including credentials. May return
	 * <code>null</code> if the <code>AuthenticationProvider</code> is unable to support
	 * authentication of the passed <code>Authentication</code> object. In such a case,
	 * the next <code>AuthenticationProvider</code> that supports the presented
	 * <code>Authentication</code> class will be tried.
	 * @throws AuthenticationException if authentication fails.
	 */
	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {

		T resouceOwnerBaseAuthentication = (T) authentication;

		OAuth2ClientAuthenticationToken clientPrincipal = getAuthenticatedClientElseThrowInvalidClient(
				resouceOwnerBaseAuthentication);

		RegisteredClient registeredClient = clientPrincipal.getRegisteredClient();
		checkClient(registeredClient);

		Set<String> authorizedScopes;
		// Default to configured scopes
		if (!CollectionUtils.isEmpty(resouceOwnerBaseAuthentication.getScopes())) {
			for (String requestedScope : resouceOwnerBaseAuthentication.getScopes()) {
				if (!registeredClient.getScopes().contains(requestedScope)) {
					throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_SCOPE);
				}
			}
			authorizedScopes = new LinkedHashSet<>(resouceOwnerBaseAuthentication.getScopes());
		}
		else {
			authorizedScopes = new LinkedHashSet<>();
		}

		Map<String, Object> reqParameters = resouceOwnerBaseAuthentication.getAdditionalParameters();
		try {

			UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken = buildToken(reqParameters);

			LOGGER.debug("got usernamePasswordAuthenticationToken=" + usernamePasswordAuthenticationToken);

			Authentication usernamePasswordAuthentication = authenticationManager
				.authenticate(usernamePasswordAuthenticationToken);
			enforceTotp(usernamePasswordAuthentication, reqParameters);

			// @formatter:off
			DefaultOAuth2TokenContext.Builder tokenContextBuilder = DefaultOAuth2TokenContext.builder()
					.registeredClient(registeredClient)
					.principal(usernamePasswordAuthentication)
					.authorizationServerContext(AuthorizationServerContextHolder.getContext())
					.authorizedScopes(authorizedScopes)
					.authorizationGrantType(GrantTypes.PASSWORD)
					.authorizationGrant(resouceOwnerBaseAuthentication);
			// @formatter:on

			OAuth2Authorization.Builder authorizationBuilder = OAuth2Authorization
				.withRegisteredClient(registeredClient)
				.id(UUID.randomUUID().toString())
				.principalName(usernamePasswordAuthentication.getName())
				.authorizationGrantType(GrantTypes.PASSWORD)
				// 0.4.0 新增的方法
				.authorizedScopes(authorizedScopes);

			// ----- Access token -----
			OAuth2TokenContext tokenContext = tokenContextBuilder.tokenType(OAuth2TokenType.ACCESS_TOKEN).build();
			OAuth2Token generatedAccessToken = this.tokenGenerator.generate(tokenContext);
			if (generatedAccessToken == null) {
				OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR,
						"The token generator failed to generate the access token.", ERROR_URI);
				throw new OAuth2AuthenticationException(error);
			}
			OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
					generatedAccessToken.getTokenValue(), generatedAccessToken.getIssuedAt(),
					generatedAccessToken.getExpiresAt(), tokenContext.getAuthorizedScopes());
			if (generatedAccessToken instanceof ClaimAccessor accessor) {
				authorizationBuilder.token(accessToken,
							(metadata) -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
									accessor.getClaims()))
					// 0.4.0 新增的方法
					.authorizedScopes(authorizedScopes)
					.attribute(Principal.class.getName(), usernamePasswordAuthentication);
			}
			else {
				authorizationBuilder.accessToken(accessToken);
			}

			// ----- Refresh token -----
			OAuth2RefreshToken refreshToken = null;
			if (registeredClient.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN) &&
			// Do not issue refresh token to public client
					!clientPrincipal.getClientAuthenticationMethod().equals(ClientAuthenticationMethod.NONE)) {

				if (this.refreshTokenGenerator != null) {
					Instant issuedAt = Instant.now();
					Instant expiresAt = issuedAt.plus(registeredClient.getTokenSettings().getRefreshTokenTimeToLive());
					refreshToken = new OAuth2RefreshToken(this.refreshTokenGenerator.get(), issuedAt, expiresAt);
				}
				else {
					tokenContext = tokenContextBuilder.tokenType(OAuth2TokenType.REFRESH_TOKEN).build();
					OAuth2Token generatedRefreshToken = this.tokenGenerator.generate(tokenContext);
					if (!(generatedRefreshToken instanceof OAuth2RefreshToken)) {
						OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR,
								"The token generator failed to generate the refresh token.", ERROR_URI);
						throw new OAuth2AuthenticationException(error);
					}
					refreshToken = (OAuth2RefreshToken) generatedRefreshToken;
				}
				authorizationBuilder.refreshToken(refreshToken);
			}

			OAuth2Authorization authorization = authorizationBuilder.build();

			this.authorizationService.save(authorization);

			LOGGER.debug("returning OAuth2AccessTokenAuthenticationToken");

			return new OAuth2AccessTokenAuthenticationToken(registeredClient, clientPrincipal, accessToken,
					refreshToken, Objects.requireNonNull(authorization.getAccessToken().getClaims()));

		}
		catch (OAuth2AuthenticationException ex) {
			throw ex;
		}
		catch (AuthenticationException ex) {
			LOGGER.error("problem in authenticate", ex);
			throw oAuth2AuthenticationException(authentication, ex);
		}
		catch (Exception ex) {
			// 这个 try 块里不只有认证：还有 enforceTotp() 这类对 upms 的内部调用。
			// 内部调用失败抛的是 HttpClientErrorException 之类，原来一律强转
			// (AuthenticationException) → ClassCastException，把一个可诊断的下游错误
			// 变成 /oauth2/token 的 500，前端只看到"登录失败"，日志里也只剩强转异常。
			// 2026-07-31 实测：upms 未发布 /totp/login-check 时，auth 调它得 424，
			// 然后就是这里炸掉，整站后台登不进去；同签名报错自 07-14 起已出现数十次。
			LOGGER.error("problem in authenticate", ex);
			throw new OAuth2AuthenticationException(
					new OAuth2Error(org.springframework.security.oauth2.core.OAuth2ErrorCodes.SERVER_ERROR,
							"登录服务异常，请稍后重试", ERROR_URI));
		}

	}

	private void enforceTotp(Authentication authentication, Map<String, Object> reqParameters) {
		if (!(authentication.getPrincipal() instanceof BaseUser user)) return;
		TotpLoginRequest request = new TotpLoginRequest();
		request.setUserId(user.getId());
		request.setTenantId(user.getTenantId());
		request.setCode((String) reqParameters.get("totp_code"));
		R<Boolean> result = userApi.checkTotpForLogin(request, SecurityConstants.FROM_IN);
		if (result == null || !Boolean.TRUE.equals(result.getData())) {
			throw new OAuth2AuthenticationException(new OAuth2Error("mfa_required", "mfa_required:需要动态验证码", ERROR_URI));
		}
	}

	/**
	 * 登录异常转换为oauth2异常
	 * @param authentication 身份验证
	 * @param authenticationException 身份验证异常
	 * @return {@link OAuth2AuthenticationException}
	 */
	private OAuth2AuthenticationException oAuth2AuthenticationException(Authentication authentication,
			AuthenticationException authenticationException) {
		if (authenticationException instanceof UsernameNotFoundException) {
			return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodesExpand.USERNAME_NOT_FOUND,
					this.messages.getMessage("JdbcDaoImpl.notFound", new Object[] { authentication.getName() },
							"Username {0} not found"),
					""));
		}
		if (authenticationException instanceof BadCredentialsException) {
			return new OAuth2AuthenticationException(
					new OAuth2Error(OAuth2ErrorCodesExpand.BAD_CREDENTIALS, this.messages.getMessage(
							"AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad credentials"), ""));
		}
		if (authenticationException instanceof LockedException) {
			return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodesExpand.USER_LOCKED,
					this.messages.getMessage("AbstractUserDetailsAuthenticationProvider.locked", "User account is locked"),
					""));
		}
		if (authenticationException instanceof DisabledException) {
			return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodesExpand.USER_DISABLE,
					this.messages.getMessage("AbstractUserDetailsAuthenticationProvider.disabled", "User is disabled"),
					""));
		}
		if (authenticationException instanceof AccountExpiredException) {
			return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodesExpand.USER_EXPIRED, this.messages
				.getMessage("AbstractUserDetailsAuthenticationProvider.expired", "User account has expired"), ""));
		}
		if (authenticationException instanceof CredentialsExpiredException) {
			return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodesExpand.CREDENTIALS_EXPIRED,
					this.messages.getMessage("AbstractUserDetailsAuthenticationProvider.credentialsExpired",
							"User credentials have expired"),
					""));
		}
		if (authenticationException instanceof ScopeException) {
			return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_SCOPE,
					this.messages.getMessage("AbstractAccessDecisionManager.accessDenied", "invalid_scope"), ""));
		}
		return new OAuth2AuthenticationException(authenticationException.getMessage());
	}

	private OAuth2ClientAuthenticationToken getAuthenticatedClientElseThrowInvalidClient(
			Authentication authentication) {

		OAuth2ClientAuthenticationToken clientPrincipal = null;

		if (OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication.getPrincipal().getClass())) {
			clientPrincipal = (OAuth2ClientAuthenticationToken) authentication.getPrincipal();
		}

		if (clientPrincipal != null && clientPrincipal.isAuthenticated()) {
			return clientPrincipal;
		}

		throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
	}

}
