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
 * spu规格
 *
 * @date 2019-08-13 16:56:46
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goods_spu_spec")
@Schema(description = "spu规格")
public class GoodsSpuSpec extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
   * spu_id
   */
	@Schema(description = "spu_id")
    private String spuId;
    /**
   * spec_id
   */
	@Schema(description = "spec_id")
    private String specId;
	/**
	 * 规格名
	 */
	@Schema(description = "规格名")
	private String specName;
	/**
	 * 排序字段
	 */
	@Schema(description = "排序字段")
	private Integer sort;

}
