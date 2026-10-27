/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 登录日志表
 *
 * @date 2020-03-28 16:39:04
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_log_login")
@Schema(description = "登录日志表")
public class SysLogLogin extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 创建者ID
     */
    @Schema(description = "创建者ID")
    private String createId;
    /**
     * 创建者
     */
    @Schema(description = "创建者")
    @TableField(fill = FieldFill.INSERT)
	private String createBy;
    /**
     * IP地址
     */
    @Schema(description = "IP地址")
    private String remoteAddr;
    /**
     * 用户代理
     */
    @Schema(description = "用户代理")
    private String userAgent;
    /**
     * 请求URI
     */
    @Schema(description = "请求URI")
    private String requestUri;
    /**
     * 操作提交的数据
     */
    @Schema(description = "操作提交的数据")
    private String params;
    /**
     * 地址信息
     */
    @Schema(description = "地址信息")
    private String address;
	/**
	 * 日志类型
	 */
	@Schema(description = "日志类型")
	@NotNull(message = "日志类型不能为空")
	private String type;
	/**
	 * 异常信息
	 */
	@Schema(description = "异常信息")
	private String exception;
    /**
     * 登录类型
     */
    @Schema(description = "登录类型")
    @NotNull(message = "登录类型不能为空")
    private String grantType;
}
