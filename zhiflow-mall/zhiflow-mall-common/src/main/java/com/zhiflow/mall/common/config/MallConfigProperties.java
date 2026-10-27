/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 商城相关配置
 *
 * @author
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "base.mall")
public class MallConfigProperties {

	/**
	 * 支付回调地址的域名（拼成 {notifyHost}/mallapi/paynotify/{渠道}/{租户}）
	 */
	private String notifyHost;

	/**
	 * 用户默认头像
	 */
	private String userDefaultAvatar;
}
