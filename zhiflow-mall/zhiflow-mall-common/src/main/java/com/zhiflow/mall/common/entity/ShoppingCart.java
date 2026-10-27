/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.zhiflow.common.core.entity.base.TenantEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 购物车
 *
 * @date 2019-08-29 21:27:33
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("shopping_cart")
@Schema(description = "购物车")
public class ShoppingCart extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
	/**
	 * 店铺Id
	 */
	@Schema(description = "店铺Id")
	private String shopId;
    /**
   * 用户编号
   */
	@Schema(description = "用户编号")
    private String userId;
    /**
   * 商品 SPU 编号
   */
	@Schema(description = "spuId")
    private String spuId;
    /**
   * 商品 SKU 编号
   */
	@Schema(description = "skuId")
    private String skuId;
    /**
   * 商品购买数量
   */
	@Schema(description = "商品购买数量")
    private Integer quantity;
	/**
	 * 加入时价格
	 */
	@Schema(description = "加入时价格")
	private BigDecimal addPrice;
	/**
	 * 加入时的spu名字
	 */
	@Schema(description = "加入时的spu名字")
	private String spuName;
	private String pageType;
	/**
	 * 加入时的规格信息
	 */
	@Schema(description = "加入时的规格信息")
	private String specInfo;
	/**
	 * 图片
	 */
	@Schema(description = "图片")
	private String picUrl;




	@TableField(exist = false)
	private GoodsSpu goodsSpu;

	@TableField(exist = false)
	private ShopInfo shopInfo;

	@TableField(exist = false)
	private GoodsSku goodsSku;

	@TableField(exist = false)
	private List<GoodsSkuSpecValue> specs;
}
