package com.zhiflow.auth.core;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.security.entity.BaseUser;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimsContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimsSet;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * token 输出增强
 *
 * @author 辰
 * @date 2023年10月26日18:27:47
 */
public class CustomeOAuth2TokenCustomizer implements OAuth2TokenCustomizer<OAuth2TokenClaimsContext> {

	/**
	 * Customize the OAuth 2.0 Token attributes.
	 * @param context the context containing the OAuth 2.0 Token attributes
	 */
	@Override
	public void customize(OAuth2TokenClaimsContext context) {
		OAuth2TokenClaimsSet.Builder claims = context.getClaims();
		String clientId = context.getAuthorizationGrant().getName();
		claims.claim(SecurityConstants.CLIENT_ID, clientId);
		// 客户端模式不返回具体用户信息
		if (SecurityConstants.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType().getValue())) {
			return;
		}

		BaseUser oAuthUser = (BaseUser) context.getPrincipal().getPrincipal();
		claims.claim(SecurityConstants.DETAILS_USER, oAuthUser);
		claims.claim(SecurityConstants.DETAILS_USER_ID, oAuthUser.getId());
		claims.claim(SecurityConstants.USERNAME, oAuthUser.getUsername());
	}

}
