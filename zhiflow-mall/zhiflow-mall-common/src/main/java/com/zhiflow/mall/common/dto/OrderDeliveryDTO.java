/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单发货参数
 *
 * @date 2019-08-13 10:18:34
 */
@Data
@Schema(description = "订单发货参数")
public class OrderDeliveryDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

	/**
	 * 订单ID
	 */
	@Schema(description = "订单ID")
	@NotNull
	private String orderId;
	/**
	 * 订单详情ID，多个用’,‘隔开
	 */
	@Schema(description = "订单详情ID，多个用’,‘隔开")
	@NotNull
	private String orderItemIds;
	/**
	 * 订单物流ID，修改发货时必传
	 */
	@Schema(description = "订单物流ID，修改发货时必传")
	private String orderLogisticsId;
	/**
	 * 发货类型（1、发物流 2、配送 3、虚拟发货）
	 */
	@Schema(description = "发货类型（1、发物流 2、配送 3、虚拟发货）")
	@NotNull
	private String deliveryType;
	/**
	 * 物流商家编码
	 */
	@Schema(description = "物流商家编码")
	@NotNull
	private String logisticsCode;
	/**
	 * 物流单号
	 */
	@Schema(description = "物流单号")
	private String logisticsNo;
	/**
	 * 面单模板id
	 */
	@Schema(description = "面单模板id")
	private String labelTemplateId;

	/**
	 * 店铺物流账号id
	 */
	@Schema(description = "店铺物流账号id")
	private String shopLogisticsAccountId;
	/**
	 * 收件人姓名
	 */
	@NotNull
	@Schema(description = "收件人姓名")
	private String recName;
	/**
	 * 收件人电话
	 */
	@NotNull
	@Schema(description = "收件人电话")
	private String recTel;
	/**
	 * 收件人地址
	 */
	@NotNull
	@Schema(description = "收件人地址")
	private String recAddress;
	/**
	 * 寄件人姓名
	 */
	@NotNull
	@Schema(description = "寄件人姓名")
	private String sendName;
	/**
	 * 寄件人电话
	 */
	@NotNull
	@Schema(description = "寄件人电话")
	private String sendTel;
	/**
	 * 寄件人地址
	 */
	@NotNull
	@Schema(description = "寄件人地址")
	private String sendAddress;
	/**
	 * 重量KG
	 */
	@NotNull
	@Schema(description = "重量KG")
	private BigDecimal weight;
}
