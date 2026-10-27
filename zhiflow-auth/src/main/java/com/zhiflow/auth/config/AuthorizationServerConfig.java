package com.zhiflow.auth.config;

import org.springframework.security.config.Customizer;

import com.zhiflow.api.upms.LogLoginApi;
import com.zhiflow.api.upms.UserApi;
import com.zhiflow.auth.core.BaseDaoAuthenticationProvider;
import com.zhiflow.auth.core.CustomeOAuth2AccessTokenGenerator;
import com.zhiflow.auth.core.CustomeOAuth2TokenCustomizer;
import com.zhiflow.auth.core.FormIdentityLoginConfigurer;
import com.zhiflow.auth.handler.BaseAuthenticationFailureEventHandler;
import com.zhiflow.auth.handler.BaseAuthenticationSuccessEventHandler;
import com.zhiflow.auth.password.OAuth2ResourceOwnerPasswordAuthenticationConverter;
import com.zhiflow.auth.password.OAuth2ResourceOwnerPasswordAuthenticationProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.web.authentication.*;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.DelegatingAuthenticationConverter;

import java.util.Arrays;

/**
 * @author 辰
 * @date 2023年10月27日09:40:35
 *
 * 认证服务器配置
 */
@Configuration
public class AuthorizationServerConfig {

	private final OAuth2AuthorizationService authorizationService;
	private final LogLoginApi logLoginHttpClient;
	private final UserApi userApi;

	public AuthorizationServerConfig(
			OAuth2AuthorizationService authorizationService,
			@Lazy LogLoginApi logLoginHttpClient, @Lazy UserApi userApi) {
		this.authorizationService = authorizationService;
		this.logLoginHttpClient = logLoginHttpClient;
		this.userApi = userApi;
	}

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
		OAuth2AuthorizationServerConfigurer authorizationServerConfigurer = new OAuth2AuthorizationServerConfigurer();

		// Spring Security 7（授权服务器已并入 spring-security-config）：configurer 必须先通过 http.with(..) 绑定到 HttpSecurity，
		// 之后才能调 getEndpointsMatcher()（内部要用 builder 造 PathPatternRequestMatcher），
		// 否则抛 "securityBuilder cannot be null"。6.x 时代「先 securityMatcher(getEndpointsMatcher()) 再 apply」的顺序在 7 上起不来。
		http.with(authorizationServerConfigurer, configurer -> configurer
				.tokenEndpoint((tokenEndpoint) -> {// 个性化认证授权端点
					tokenEndpoint.accessTokenRequestConverter(accessTokenRequestConverter()) // 注入自定义的授权认证Converter
							.accessTokenResponseHandler(new BaseAuthenticationSuccessEventHandler(logLoginHttpClient)) // 登录成功处理器
							.errorResponseHandler(new BaseAuthenticationFailureEventHandler(logLoginHttpClient));// 登录失败处理器
				})
				.clientAuthentication(oAuth2ClientAuthenticationConfigurer -> // 个性化客户端认证
						oAuth2ClientAuthenticationConfigurer.errorResponseHandler(new BaseAuthenticationFailureEventHandler(logLoginHttpClient)))// 处理客户端认证异常
				.authorizationService(authorizationService)// redis存储token的实现
				.authorizationServerSettings(
						AuthorizationServerSettings.builder().build()));

		// 使用 securityMatcher 限制此 filter chain 只处理 OAuth2 授权服务器端点
		http.securityMatcher(authorizationServerConfigurer.getEndpointsMatcher());
		http.authorizeHttpRequests(authorizeRequests -> authorizeRequests.anyRequest().authenticated());
		http.with(new FormIdentityLoginConfigurer(), Customizer.withDefaults());
		DefaultSecurityFilterChain securityFilterChain = http.build();

		// 注入自定义授权模式实现
		addCustomOAuth2GrantAuthenticationProvider(http);
		return securityFilterChain;
	}

	/**
	 * 令牌生成规则实现 </br>
	 * client:username:uuid
	 * @return OAuth2TokenGenerator
	 */
	@Bean
	public OAuth2TokenGenerator oAuth2TokenGenerator() {
		CustomeOAuth2AccessTokenGenerator accessTokenGenerator = new CustomeOAuth2AccessTokenGenerator();
		// 注入Token 增加关联用户信息
		accessTokenGenerator.setAccessTokenCustomizer(new CustomeOAuth2TokenCustomizer());

		return new DelegatingOAuth2TokenGenerator(accessTokenGenerator, new OAuth2RefreshTokenGenerator());
	}

	/**
	 * request -> xToken 注入请求转换器
	 * @return DelegatingAuthenticationConverter
	 */
	private AuthenticationConverter accessTokenRequestConverter() {
		return new DelegatingAuthenticationConverter(Arrays.asList(
				new OAuth2ResourceOwnerPasswordAuthenticationConverter(),
				new OAuth2RefreshTokenAuthenticationConverter(),
				new OAuth2ClientCredentialsAuthenticationConverter(),
				new OAuth2AuthorizationCodeAuthenticationConverter(),
				new OAuth2AuthorizationCodeRequestAuthenticationConverter()));
	}

	/**
	 * 注入授权模式实现提供方
	 *
	 * 1. 密码模式 </br>
	 * 2. 短信登录 </br>
	 *
	 */
	@SuppressWarnings("unchecked")
	private void addCustomOAuth2GrantAuthenticationProvider(HttpSecurity http) {
		AuthenticationManager authenticationManager = http.getSharedObject(AuthenticationManager.class);
		OAuth2AuthorizationService authorizationService = http.getSharedObject(OAuth2AuthorizationService.class);

		OAuth2ResourceOwnerPasswordAuthenticationProvider resourceOwnerPasswordAuthenticationProvider = new OAuth2ResourceOwnerPasswordAuthenticationProvider(
				authenticationManager, authorizationService, oAuth2TokenGenerator(), userApi);

		// 处理 UsernamePasswordAuthenticationToken
		http.authenticationProvider(new BaseDaoAuthenticationProvider());
		// 处理 OAuth2ResourceOwnerPasswordAuthenticationToken
		http.authenticationProvider(resourceOwnerPasswordAuthenticationProvider);
	}
}
