package com.zhiflow.auth.handler;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.extra.servlet.ServletUtil;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.ClientIpResolver;
import com.zhiflow.common.core.util.IpUtils;
import com.zhiflow.common.core.util.SpringContextHolder;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.log.event.SysLogEvent;
import com.zhiflow.common.log.util.SysLogUtils;
import com.zhiflow.common.security.component.BaseCustomOAuth2AccessTokenResponseHttpMessageConverter;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.upms.common.entity.SysLog;
import com.zhiflow.upms.common.dto.SysLogLoginPayload;
import com.zhiflow.api.upms.LogLoginApi;
import com.zhiflow.common.data.tenant.TenantAwareAsync;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * @author 辰
 * @date 2023年10月26日18:21:55
 */
@Slf4j
@AllArgsConstructor
public class BaseAuthenticationSuccessEventHandler implements AuthenticationSuccessHandler {

	private final HttpMessageConverter<OAuth2AccessTokenResponse> accessTokenHttpResponseConverter = new BaseCustomOAuth2AccessTokenResponseHttpMessageConverter();

	private final LogLoginApi logLoginApi;
	/**
	 * Called when a user has been successfully authenticated.
	 * @param request the request which caused the successful authentication
	 * @param response the response
	 * @param authentication the <tt>Authentication</tt> object which was created during
	 * the authentication process.
	 */
	@SneakyThrows
	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
										Authentication authentication) {
		AuthEventMetrics.record(AuthEventMetrics.successKind(request.getParameter(OAuth2ParameterNames.GRANT_TYPE)));
		OAuth2AccessTokenAuthenticationToken accessTokenAuthentication = (OAuth2AccessTokenAuthenticationToken) authentication;
		Map<String, Object> map = accessTokenAuthentication.getAdditionalParameters();
		if (MapUtil.isNotEmpty(map)) {
			// 发送异步日志事件
			BaseUser userInfo = (BaseUser) map.get(SecurityConstants.DETAILS_USER);
			TenantContextHolder.setTenantId(userInfo.getTenantId());
			log.info("用户：{} 登录成功", userInfo.getUsername());
			SecurityContextHolder.getContext().setAuthentication(accessTokenAuthentication);
			// 在请求线程上取好：异步线程里请求可能已结束。开关关时仍是 getRemoteAddr()（经网关部署时恒为网关容器地址），
			// 开时是 openresty 覆盖写的真实客户端（CARD-06）。
			String remoteAddr = ClientIpResolver.resolve(request, request::getRemoteAddr);
			TenantAwareAsync.runAsync(() -> {
				TenantContextHolder.setTenantId(userInfo.getTenantId());
				SysLogLoginPayload sysLogLogin = new SysLogLoginPayload();
				sysLogLogin.setType(CommonConstants.LOG_TYPE_0);
				sysLogLogin.setGrantType(request.getParameter(OAuth2ParameterNames.GRANT_TYPE));
				sysLogLogin.setCreateId(userInfo.getId());
				sysLogLogin.setCreateBy(userInfo.getUsername());
				sysLogLogin.setRemoteAddr(remoteAddr);
				sysLogLogin.setRequestUri(URLUtil.getPath(request.getRequestURI()));
				sysLogLogin.setUserAgent(request.getHeader("user-agent"));
//			sysLogLogin.setParams(HttpUtil.toParams(request.getParameterMap()));
				sysLogLogin.setTenantId(userInfo.getTenantId());
				String address = IpUtils.getAddresses(sysLogLogin.getRemoteAddr());
				sysLogLogin.setAddress(address);
				logLoginApi.saveLogLogin(sysLogLogin, SecurityConstants.FROM_IN);
			});
		}

		// 输出token
		sendAccessTokenResponse(request, response, authentication);
	}

	private void sendAccessTokenResponse(HttpServletRequest request, HttpServletResponse response,
										 Authentication authentication) throws IOException {

		OAuth2AccessTokenAuthenticationToken accessTokenAuthentication = (OAuth2AccessTokenAuthenticationToken) authentication;

		OAuth2AccessToken accessToken = accessTokenAuthentication.getAccessToken();
		OAuth2RefreshToken refreshToken = accessTokenAuthentication.getRefreshToken();
		Map<String, Object> additionalParameters = accessTokenAuthentication.getAdditionalParameters();

		OAuth2AccessTokenResponse.Builder builder = OAuth2AccessTokenResponse.withToken(accessToken.getTokenValue())
				.tokenType(accessToken.getTokenType())
				.scopes(accessToken.getScopes());
		if (accessToken.getIssuedAt() != null && accessToken.getExpiresAt() != null) {
			builder.expiresIn(ChronoUnit.SECONDS.between(accessToken.getIssuedAt(), accessToken.getExpiresAt()));
		}
		if (refreshToken != null) {
			builder.refreshToken(refreshToken.getTokenValue());
		}
		if (!CollectionUtils.isEmpty(additionalParameters)) {
			builder.additionalParameters(additionalParameters);
		}
		OAuth2AccessTokenResponse accessTokenResponse = builder.build();
		ServletServerHttpResponse httpResponse = new ServletServerHttpResponse(response);

		// 无状态 注意删除 context 上下文的信息
		SecurityContextHolder.clearContext();

		this.accessTokenHttpResponseConverter.write(accessTokenResponse, null, httpResponse);
	}
}
