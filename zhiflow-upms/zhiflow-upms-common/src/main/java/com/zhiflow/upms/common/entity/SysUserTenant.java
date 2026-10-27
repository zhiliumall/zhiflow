/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 系统管理员用户绑定租户关联表
 *
 * @date 2021-11-11 12:30:59
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_tenant")
@Schema(description = "系统管理员用户绑定租户关联表")
public class SysUserTenant extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 用户ID
     */
    @NotNull(message = "用户ID不能为空")
    @Schema(description = "用户ID")
    private String userId;

    /**
     * 是否为默认租户（0否 1是）
     */
    @Schema(description = "是否为默认租户（0否 1是）")
    private Integer isDefault;

 }
