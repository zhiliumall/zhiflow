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
 * 规格值
 *
 * @date 2019-08-13 16:11:05
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goods_spec_value")
@Schema(description = "规格值")
public class GoodsSpecValue extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
   * 规格Id
   */
	@Schema(description = "规格Id")
    private String specId;
    /**
   * 名称
   */
	@Schema(description = "名称")
    private String name;

}
