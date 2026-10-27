package com.zhiflow.api.weixin.dto;

import lombok.Data;

/**
 * 发货联系方式，对应 SDK 类型
 * {@code cn.binarywang.wx.miniapp.bean.shop.request.shipping.ContactBean}。
 *
 * <p>⚠️ 两个字段都是<b>手机号后四位</b>（微信要求：寄件人/收件人联系方式，仅在
 * {@code logisticsType=1} 真实物流时才需要）。本仓两个调用点都<b>从不设置</b>它
 * （走的是虚拟发货 {@code logisticsType=3/4}），所以实际永远是 {@code null}。
 *
 * <p>仍然保留而不是剔除：这是<b>请求方向</b>的外部 SDK 类型，字段语义由微信定义，
 * 服务端把整个对象原样转发给微信 SDK、自己不挑字段用。请求方向砍字段 =
 * 砍掉将来接真实物流时使用这些字段的能力，与响应方向「没人读就不带」的取舍方向相反。
 */
@Data
public class WxOrderShippingContactDTO {

	/** 寄件人联系方式（手机号后四位）。本仓调用点从不设置。 */
	private String consignorContact;

	/** 收件人联系方式（手机号后四位）。本仓调用点从不设置。 */
	private String receiverContact;

}
