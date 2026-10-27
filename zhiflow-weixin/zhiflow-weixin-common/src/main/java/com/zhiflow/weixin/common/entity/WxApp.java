/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.weixin.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.validation.constraints.NotNull;

/**
 * 微信账号配置
 *
 * @date 2019-03-23 21:26:35
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "微信账号")
@TableName(value = "wx_app")
public class WxApp extends TenantEntity {

    /**
   * 应用ID
   */
	@Schema(description = "应用ID")
    @TableId(type = IdType.INPUT)
	@NotNull(message = "应用ID不能为空")
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
   * 应用类型(1:小程序，2:公众号)
   */
	@Schema(description = "应用类型(1:小程序，2:公众号)")
	@NotNull(message = "应用类型不能为空")
    private String appType;
    /**
   * 应用密钥
   */
	@Schema(description = "应用密钥")
    private String secret;
    /**
   * token
   */
	@Schema(description = "token")
    private String token;
    /**
   * EncodingAESKey
   */
	@Schema(description = "EncodingAESKey")
    private String aesKey;
    /**
   * 微信号名称
   */
	@Schema(description = "微信号名称")
    private String name;
	/**
	 * logo
	 */
	@Schema(description = "logo")
	private String logo;

    /**
   * 备注信息
   */
	@Schema(description = "备注信息")
    private String remarks;
}
