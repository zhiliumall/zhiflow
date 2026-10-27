/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.zhiflow.common.core.annotation.HashidsId;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 下单参数
 *
 * @date 2019-08-13 10:18:34
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "下单参数")
public class PlaceOrderSkuDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

	/**
	 * 商品Id
	 */
	@HashidsId
	@Schema(description = "商品Id")
	private String spuId;
    /**
	 * skuId
	 */
	@HashidsId
	@Schema(description = "skuId")
    private String skuId;

	/**
	 * 数量
	 */
	@Schema(description = "数量")
	private Integer quantity;
	/**
	 * 支付金额
	 */
	@Schema(description = "支付金额")
	private BigDecimal paymentPrice;
	/**
	 * 运费金额
	 */
	@Schema(description = "运费金额")
	private BigDecimal freightPrice;
	/**
	 * 支付积分
	 */
	@Schema(description = "支付积分")
	private Integer paymentPoints;
	/**
	 * 电子券支付金额
	 */
	@Schema(description = "电子券支付金额")
	private BigDecimal paymentCouponPrice;
	/**
	 * 积分抵扣金额
	 */
	@Schema(description = "积分抵扣金额")
	private BigDecimal paymentPointsPrice;

	/**
	 * 页面来源: normal（普通商品）、seckill（秒杀商品）、flash（限时折扣）、newcomer（新人商品）、distribution （分销）
	 */
	@Schema(description = "页面来源: normal（普通商品）、seckill（秒杀商品）、flash（限时折扣）、newcomer（新人商品）、distribution （分销）")
	private String pageType;



}
