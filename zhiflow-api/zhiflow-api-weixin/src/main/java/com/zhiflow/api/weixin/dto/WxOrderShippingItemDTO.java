package com.zhiflow.api.weixin.dto;

import lombok.Data;

/**
 * 一条发货记录，对应 SDK 类型
 * {@code cn.binarywang.wx.miniapp.bean.shop.request.shipping.ShippingListBean}。
 */
@Data
public class WxOrderShippingItemDTO {

	/** 物流单号。虚拟发货下不设置。 */
	private String trackingNo;

	/** 物流公司编码。虚拟发货下不设置。 */
	private String expressCompany;

	/** 商品信息（本仓两个调用点唯一真的设置的字段，取订单名截断到 39 字符）。 */
	private String itemDesc;

	/** 联系方式。本仓调用点从不设置。 */
	private WxOrderShippingContactDTO contact;

}
