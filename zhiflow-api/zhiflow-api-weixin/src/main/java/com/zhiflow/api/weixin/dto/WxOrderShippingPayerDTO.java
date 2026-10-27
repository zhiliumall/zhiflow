package com.zhiflow.api.weixin.dto;

import lombok.Data;

/**
 * 支付者，对应 SDK 类型
 * {@code cn.binarywang.wx.miniapp.bean.shop.request.shipping.PayerBean}。
 */
@Data
public class WxOrderShippingPayerDTO {

	/** 支付者 openId。 */
	private String openid;

}
