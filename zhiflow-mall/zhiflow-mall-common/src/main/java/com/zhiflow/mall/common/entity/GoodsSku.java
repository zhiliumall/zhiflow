/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.zhiflow.common.core.annotation.HashidsId;
import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 商品sku
 *
 * @date 2019-08-13 10:18:34
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goods_sku")
@Schema(description = "商品sku")
public class GoodsSku extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    @HashidsId
    private String id;

	/**
	 * 店铺Id
	 */
	@Schema(description = "店铺Id")
	private String shopId;
    /**
   * sku编码
   */
	@Schema(description = "sku编码")
    private String skuCode;
    /**
   * 商品Id
   */
	@Schema(description = "商品Id")
	@HashidsId
    private String spuId;
    /**
   * 图片
   */
	@Schema(description = "图片")
    private String picUrl;
    /**
   * 销售价格
   */
	@Schema(description = "销售价格")
    private BigDecimal salesPrice;
    /**
   * 市场价
   */
	@Schema(description = "市场价")
    private BigDecimal marketPrice;
    /**
   * 成本价
   */
	@Schema(description = "成本价")
    private BigDecimal costPrice;
    /**
   * 库存
   */
	@Schema(description = "库存")
    private Integer stock;
    /**
   * 重量
   */
	@Schema(description = "重量")
    private BigDecimal weight;
    /**
   * 体积
   */
	@Schema(description = "体积")
    private BigDecimal volume;
	/**
	 * 是否启用1、是；0否
	 */
	@Schema(description = "是否启用1、是；0否")
	private String enable;
	/**
	 * 版本号
	 */
	@Version
	private Integer version;

	@TableField(exist = false)
	private List<GoodsSkuSpecValue> specs;

	@TableField(exist = false)
	private String name;

	@TableField(exist = false)
	private GoodsSpu goodsSpu;

}
