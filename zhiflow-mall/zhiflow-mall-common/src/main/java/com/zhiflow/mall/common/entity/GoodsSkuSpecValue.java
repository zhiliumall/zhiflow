/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 商品sku规格值
 *
 * @date 2019-08-13 10:19:09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goods_sku_spec_value")
@Schema(description = "商品sku规格值")
public class GoodsSkuSpecValue extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

	/**
	 * spu_id
	 */
	@Schema(description = "spu_id")
	private String spuId;
    /**
   * sku_id
   */
	@Schema(description = "sku_id")
    private String skuId;
    /**
   * 规格值id
   */
	@Schema(description = "规格值id")
    private String specValueId;
	/**
	 * 规格id
	 */
	@Schema(description = "规格id")
	@TableField(exist = false)
	private String specId;
	/**
	 * 规格值名
	 */
	@Schema(description = "规格值名")
	@TableField(exist = false)
	private String specValueName;
	/**
	 * 排序字段
	 */
	@Schema(description = "排序字段")
	private Integer sort;

}
