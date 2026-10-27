package com.zhiflow.common.core.constant;

/**
 * @author
 */
public interface SecurityConstants {
	/**
	 * 密码登录
	 */
	String PASSWORD = "password";

	/**
	 * 刷新
	 */
	String REFRESH_TOKEN = "refresh_token";
	/**
	 * 角色前缀
	 */
	String ROLE = "ROLE_";

	/**
	 * 内部
	 */
	String FROM_IN = "Y";

	/**
	 * 标志
	 */
	String FROM = "from";

	/**
	 * OAUTH URL
	 */
	String OAUTH_TOKEN_URL = "/oauth2/token";

	/**
	 * {bcrypt} 加密的特征码
	 */
	String BCRYPT = "{bcrypt}";

	/**
	 * 客户端模式
	 */
	String CLIENT_CREDENTIALS = "client_credentials";

	/**
	 * 用户ID字段
	 */
	String DETAILS_USER_ID = "user_id";

	String NOOP = "{noop}";

	/**
	 * 用户信息
	 */
	String DETAILS_USER = "user_info";

	/**
	 * 客户端ID
	 */
	String CLIENT_ID = "clientId";

	/**
	 * 用户名
	 */
	String USERNAME = "username";
}
