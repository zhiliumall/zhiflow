package com.zhiflow.common.core.constant.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author
 * 社交登录类型
 */
@Getter
@AllArgsConstructor
public enum LoginTypeEnum {
	/**
	 * 账号密码登录
	 */
	PWD("PWD", "账号密码"),

	/**
	 * 验证码登录
	 */
	SMS("SMS", "验证码"),

	/**
	 * QQ登录
	 */
	QQ("QQ", "QQ"),

	/**
	 * 微信登录
	 */
	WX("WX", "微信");

	/**
	 * 类型
	 */
	private String type;
	/**
	 * 描述
	 */
	private String description;
}
