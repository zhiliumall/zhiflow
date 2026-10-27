package com.zhiflow.pay.common.dto.inside;

import lombok.Data;

/**
 * 微信支付（国内）查单的<b>请求投影</b>，取代 SDK 的
 * {@code com.github.binarywang.wxpay.bean.request.WxPayOrderQueryRequest}。理由同
 * {@link WxUnifiedOrderReq}。
 *
 * <p>3 个活调用点逐字同构，只 set {@code transactionId / outTradeNo / subMchId}；
 * {@code appid} 同 {@link WxRefundReq#getAppid()}，服务端读它但调用方从不设置。
 */
@Data
public class WxOrderQueryReq {

	/** 服务商/普通模式下的 appid。⚠️ 3 个调用方今天都不设置它。 */
	private String appid;

	/** 子商户号（服务商模式）。普通模式下服务端会把它清空。 */
	private String subMchId;

	/** 微信支付流水号。 */
	private String transactionId;

	/** 商户订单号。 */
	private String outTradeNo;

}
