package com.zhiflow.api.weixin.dto;

import lombok.Data;

/**
 * 发货信息的订单标识，对应 SDK 类型
 * {@code cn.binarywang.wx.miniapp.bean.shop.request.shipping.OrderKeyBean}。
 *
 * <p>{@code orderNumberType} 在 SDK 上是<b>原生 {@code int}</b>（1：使用原支付交易对应的商户单号；
 * 2：使用微信支付交易单号），保持原生类型，proto 侧对应裸 {@code int32}。
 */
@Data
public class WxOrderShippingKeyDTO {

	/** 订单单号类型（SDK 侧是原生 int）。 */
	private int orderNumberType;

	/** 微信支付交易单号。 */
	private String transactionId;

	/** 商户号。 */
	private String mchId;

	/** 商户内部订单号。 */
	private String outTradeNo;

}
