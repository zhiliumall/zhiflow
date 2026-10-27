package com.zhiflow.common.security.oauth2;

import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * 本项目自定义授权模式的 grant_type 常量。
 *
 * <p>Spring Security 7 删除了 {@code AuthorizationGrantType.PASSWORD}（RFC 6749 的密码授权已被 OAuth 2.1 废弃），
 * 但本项目的密码 / 短信 / 社交登录仍以 {@code grant_type=password} 与三端约定，故在此自持同值常量，
 * 已部署的协议与既有 token 不受影响。
 */
public final class GrantTypes {

	/** 与 Spring Security 6 的 {@code AuthorizationGrantType.PASSWORD} 同值。 */
	public static final AuthorizationGrantType PASSWORD = new AuthorizationGrantType("password");

	private GrantTypes() {
	}

}
