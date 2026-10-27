package com.zhiflow.common.security.util;

import cn.hutool.core.map.MapUtil;
import com.zhiflow.common.core.constant.SecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/**
 * @author 辰
 * @description OAuth2 端点工具
 * @date 2023年10月27日09:54:55
 */
@UtilityClass
public class OAuth2EndpointUtils {

	public final String ACCESS_TOKEN_REQUEST_ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc6749#section-5.2";

	/**
	 * 敏感参数列表，禁止通过 Query 参数传递
	 */
	private static final Set<String> SENSITIVE_PARAMS = Set.of(
		SecurityConstants.USERNAME,
		SecurityConstants.PASSWORD,
		"token"
	);

	/**
	 * 检查请求是否包含敏感参数在 Query String 中
	 * @param request HTTP 请求
	 * @return 如果 Query String 中包含敏感参数返回 true
	 */
	public static boolean hasSensitiveParamsInQuery(HttpServletRequest request) {
		String queryString = request.getQueryString();
		if (queryString == null || queryString.isEmpty()) {
			return false;
		}
		// 检查 Query String 中是否包含敏感参数
		String lowerQuery = queryString.toLowerCase();
		return SENSITIVE_PARAMS.stream()
			.anyMatch(param -> lowerQuery.contains(param.toLowerCase() + "="));
	}

	/**
	 * 获取请求参数（仅从 Body 中获取，拒绝 Query 参数中的敏感信息）
	 * @param request HTTP 请求
	 * @return 参数映射
	 */
	public MultiValueMap<String, String> getParameters(HttpServletRequest request) {
		// 安全检查：拒绝敏感参数通过 Query 传递
		if (hasSensitiveParamsInQuery(request)) {
			throwError(OAuth2ErrorCodes.INVALID_REQUEST, 
				"认证参数必须通过 Request Body 传递，禁止使用 Query 参数", 
				ACCESS_TOKEN_REQUEST_ERROR_URI);
		}

		Map<String, String[]> parameterMap = request.getParameterMap();
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>(parameterMap.size());
		parameterMap.forEach((key, values) -> {
			if (values.length > 0) {
				for (String value : values) {
					parameters.add(key, value);
				}
			}
		});
		return parameters;
	}

	public boolean matchesPkceTokenRequest(HttpServletRequest request) {
		return AuthorizationGrantType.AUTHORIZATION_CODE.getValue()
			.equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE))
				&& request.getParameter(OAuth2ParameterNames.CODE) != null
				&& request.getParameter(PkceParameterNames.CODE_VERIFIER) != null;
	}

	public void throwError(String errorCode, String parameterName, String errorUri) {
		OAuth2Error error = new OAuth2Error(errorCode, "OAuth 2.0 Parameter: " + parameterName, errorUri);
		throw new OAuth2AuthenticationException(error);
	}

	/**
	 * 格式化输出token 信息
	 * @param authentication 用户认证信息
	 * @param claims 扩展信息
	 * @return
	 * @throws IOException
	 */
	public OAuth2AccessTokenResponse sendAccessTokenResponse(OAuth2Authorization authentication,
			Map<String, Object> claims) {

		OAuth2AccessToken accessToken = authentication.getAccessToken().getToken();
		OAuth2RefreshToken refreshToken = authentication.getRefreshToken().getToken();

		OAuth2AccessTokenResponse.Builder builder = OAuth2AccessTokenResponse.withToken(accessToken.getTokenValue())
			.tokenType(accessToken.getTokenType())
			.scopes(accessToken.getScopes());
		if (accessToken.getIssuedAt() != null && accessToken.getExpiresAt() != null) {
			builder.expiresIn(ChronoUnit.SECONDS.between(accessToken.getIssuedAt(), accessToken.getExpiresAt()));
		}
		if (refreshToken != null) {
			builder.refreshToken(refreshToken.getTokenValue());
		}

		if (MapUtil.isNotEmpty(claims)) {
			builder.additionalParameters(claims);
		}
		return builder.build();
	}

}
