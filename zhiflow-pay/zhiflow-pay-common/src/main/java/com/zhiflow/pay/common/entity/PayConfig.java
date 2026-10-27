/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.pay.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 支付配置
 *
 * @date 2020-05-29 16:37:13
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pay_config")
@Schema(description = "支付配置")
public class PayConfig extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 创建者
     */
    @Schema(description = "创建者")
    private String createId;
    /**
     * 更新者
     */
    @Schema(description = "更新者")
    private String updateId;
    /**
     * 类型：1 微信支付；2 支付宝
     */
	@NotNull(message = "类型不能为空")
    @Schema(description = "类型：1 微信支付；2 支付宝")
    private String type;
	/**
	 * 模式：1服务商模式；2普通商户模式
	 */
	@NotNull(message = "模式不能为空")
	@Schema(description = "模式：1服务商模式；2普通商户模式")
	private String model;
	/**
	 * 微信支付/支付宝服务商的APPID
	 */
	@NotNull(message = "服务商的APPID不能为空")
	@Schema(description = "服务商的APPID")
	private String appId;
    /**
     * 微信支付商户号
     */
    @NotNull(message = "微信支付商户号不能为空")
    @Schema(description = "微信支付商户号")
    private String mchId;
    /**
     * 微信支付商户密钥/支付宝私钥
     */
    @NotNull(message = "微信支付商户密钥/支付宝私钥不能为空")
    @Schema(description = "微信支付商户密钥/支付宝私钥")
    private String mchKey;
    /**
     * 微信支付p12证书的文件路径/支付宝根证书文件路径
     */
    @NotNull(message = "微信支付p12证书的文件路径/支付宝根证书文件路径不能为空")
    @Schema(description = "微信支付p12证书的文件路径/支付宝根证书文件路径")
    private String keyPath;
	/**
	 * 微信支付apiclient_key.pem证书的文件路径/支付宝公钥证书文件路径
	 */
	@NotNull(message = "微信支付apiclient_key.pem证书的文件路径/支付宝公钥证书文件路径不能为空")
	@Schema(description = "微信支付apiclient_key.pem证书的文件路径/支付宝公钥证书文件路径")
	private String privateKeyPath;
	/**
	 * 微信支付apiclient_cert.pem证书的文件路径/支付宝应用公钥证书文件路径
	 */
	@NotNull(message = "微信支付apiclient_cert.pem证书的文件路径/支付宝应用公钥证书文件路径不能为空")
	@Schema(description = "微信支付apiclient_cert.pem证书的文件路径/支付宝应用公钥证书文件路径")
	private String privateCertPath;
	/**
	 * 微信支付apiV3证书序列号
	 */
	@NotNull(message = "微信支付apiV3证书序列号不能为空")
	@Schema(description = "微信支付apiV3证书序列号")
	private String certSerialNo;
	/**
	 * 微信支付apiV3 秘钥
	 */
	@NotNull(message = "微信支付apiV3 秘钥不能为空")
	@Schema(description = "微信支付apiV3 秘钥")
	private String apiV3Key;
}
