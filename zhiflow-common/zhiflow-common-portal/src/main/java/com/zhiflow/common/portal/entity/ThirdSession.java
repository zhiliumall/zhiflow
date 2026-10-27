/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.portal.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class ThirdSession implements Serializable {
	/**
	 * 所属租户
	 */
	private String tenantId;
	/**
	 * 微信用户ID
	 */
	private String wxUserId;
	/**
	 * 配置项ID
	 */
	private String appId;
	/**
	 * 微信sessionKey
	 */
	private String sessionKey;
	/**
	 * 用户标识
	 */
	private String openId;
	/**
	 * 商城用户ID
	 */
	private String userId;
	/**
	 * 会话签发时间(epoch millis)，用于 30 天绝对过期上限；
	 * 历史/遗漏路径无此字段时由拦截器惰性补写，自首次访问起计上限，避免活跃会话永不过期
	 */
	private Long issuedAt;
	/**
	 * 首次访问设备标识(User-Agent 摘要)，仅用于审计，不参与鉴权
	 */
	private String device;
}
