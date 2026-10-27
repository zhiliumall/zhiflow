/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.pay.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 支付配置视图对象（不包含敏感字段）
 *
 * @date 2025-01-19
 */
@Data
@Schema(description = "支付配置VO（不敏感）")
public class PayConfigVO {

	/**
	 * PK
	 */
	@Schema(description = "PK")
	private String id;

	/**
	 * 类型：1微信支付；2支付宝
	 */
	@Schema(description = "类型：1 微信支付；2 支付宝")
	private String type;

	/**
	 * 模式：1服务商模式；2普通商户模式
	 */
	@Schema(description = "模式：1服务商模式；2普通商户模式")
	private String model;

	/**
	 * 微信支付/支付宝服务商的APPID
	 */
	@Schema(description = "服务商的APPID")
	private String appId;

	/**
	 * 微信支付商户号
	 */
	@Schema(description = "微信支付商户号")
	private String mchId;

    /**
     * 逻辑删除标记（0：显示；1：隐藏）
     */
    @Schema(description = "逻辑删除标记（0：显示；1：隐藏")
    private String delFlag;
}
