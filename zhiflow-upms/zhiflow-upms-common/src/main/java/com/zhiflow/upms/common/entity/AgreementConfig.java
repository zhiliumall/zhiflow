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
 * 协议表
 *
 * @date 2024-03-27 11:02:10
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agreement_config")
@Schema(description = "协议表")
public class AgreementConfig extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 协议类型：1、会员卡协议；2、租户充值协议
     */
    @NotNull(message = "协议类型")
    @Schema(description = "协议类型：1、会员卡协议；2、租户充值协议")
    private String agreementType;
    /**
     * 协议名
     */
    @NotNull(message = "协议名不能为空")
    @Schema(description = "协议名")
    private String agreementName;
    /**
     * 协议内容
     */
    @Schema(description = "协议内容")
    private String agreementContent;

}
