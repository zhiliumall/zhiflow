/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 商城系统配置表
 *
 * @date 2025-07-22
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_config")
@Schema(description = "商城系统配置")
public class MallConfig extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 配置键
     */
    @Schema(description = "配置键")
    private String configKey;

    /**
     * 配置值
     */
    @Schema(description = "配置值")
    private String configValue;

    /**
     * 配置描述
     */
    @Schema(description = "配置描述")
    private String configDesc;

    /**
     * 配置类型（string/number/boolean/json）
     */
    @Schema(description = "配置类型")
    private String configType;

}
