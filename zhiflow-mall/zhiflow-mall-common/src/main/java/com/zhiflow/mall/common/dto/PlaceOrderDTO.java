/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.dto;

import com.zhiflow.common.core.annotation.HashidsId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.util.List;

/**
 * 下单参数
 *
 * @date 2019-08-13 10:18:34
 */
@Data
@Schema(description = "下单参数")
public class PlaceOrderDTO {
	private static final long serialVersionUID = 1L;

    /**
   * sku
   */
	@Schema(description = "sku")
    private List<PlaceOrderSkuDTO> skus;
	/**
	 * 支付方式1、货到付款；2、在线支付
	 */
	@Schema(description = "支付方式1、货到付款；2、在线支付")
	private String paymentWay;
	/**
	 * 配送方式1、普通快递；2、上门自提
	 */
	@Schema(description = "配送方式")
	private String deliveryWay;
	/**
	 * 付款方式1、微信支付
	 */
	@Schema(description = "付款方式")
	private String paymentType;
	/**
	 * 买家留言
	 */
	@Schema(description = "买家留言")
	private String userMessage;
	/**
	 * 来源应用（MA：小程序；H5：普通H5；H5-WX：微信H5；APP：app；H5-PC：PC端H5）
	 */
	@Schema(description = "应用类型")
	private String appType;
	/**
	 * 应用ID
	 */
	@Schema(description = "应用ID")
	private String appId;
	/**
	 * 用户id
	 */
	@Schema(description = "用户id")
	private String userId;
	/**
	 * 用户收货地址ID
	 */
	@HashidsId
	@Schema(description = "用户收货地址ID")
	private String userAddressId;
	/**
	 * 订单类型（0、普通订单；1、砍价订单；2、拼团订单；3、秒杀订单）
	 */
	@Schema(description = "订单类型")
	private String orderType;

	/**
	 * 店铺Id
	 */
	@HashidsId
	@Schema(description = "店铺Id")
	private String shopId;

	/**
	 * 下单客户端 IP（服务端填，写进 order_tax_log）。下单控制器一律用 {@code ClientIpResolver.trusted} 覆盖，
	 * 请求体里带的值不会生效；秒杀经 MQ 异步建单拿不到 request，只能靠这个字段带过去，所以不能做成 transient / JsonIgnore。
	 */
	@Schema(description = "服务端填写，前端无需传", accessMode = Schema.AccessMode.READ_ONLY)
	private String clientIp;

	/**
	 * 服务端内部标记：本次下单按「结算运费规则」（mall_config {@code shipping.rule}）校验运费。
	 * 只由 {@code orderAddMap} 在单店铺下单时置 true（H5 把整单运费挂在第一家店第一件商品上，
	 * 多店铺时逐店校验会误判）；秒杀 MQ 异步建单不经过 orderAddMap，恒为 false。
	 * 请求体里带的值不生效（JsonIgnore）。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private boolean freightRuleCheck;

	/**
	 * 服务端内部：控制器 {@code requireOwnShippingAddress} 已核过归属的收货地址，带给 {@code orderAddOne} 复用，
	 * 不再在下单事务里按 userAddressId 查第二次（10-22 下单链路清理）。只在本进程内传递：JsonIgnore ⇒ 请求体带不进来。
	 * {@code orderAddOne} 只在它非空、userId 与下单人一致、id 与 userAddressId 一致时才用，否则照旧查库核验。
	 * 名字不得与 {@code OrderInfo} 字段同名（copyProperties 会整个拷过去）。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private com.zhiflow.mall.common.entity.UserAddress resolvedShippingAddress;
}
