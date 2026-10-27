/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会员登录 / 注册入参。
 *
 */
@Data
@Schema(description = "会员登录 / 注册")
public class UserInfoLoginDTO implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	/** 账号密码登录的账号：用户名或手机号 */
	@Schema(description = "账号（用户名或手机号）")
	private String account;

	/** 注册时的用户名（与手机号二选一） */
	@Schema(description = "用户名")
	private String username;

	@Schema(description = "手机号")
	private String phone;

	@Schema(description = "密码")
	private String password;

	/** 短信验证码 */
	@Schema(description = "短信验证码")
	private String code;

	/** 滑块验证码二次校验 token */
	@Schema(description = "图形验证码 token")
	private String graphCode;
}
