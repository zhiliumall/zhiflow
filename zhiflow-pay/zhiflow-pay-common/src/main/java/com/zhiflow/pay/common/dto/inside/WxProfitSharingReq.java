package com.zhiflow.pay.common.dto.inside;

import lombok.Data;

/**
 * 微信支付（国内）单次分账的<b>请求投影</b>，取代 SDK 的
 * {@code com.github.binarywang.wxpay.bean.profitsharing.request.ProfitSharingRequest}。
 *
 * <p>⚠️ 本条边当前<b>零活调用点</b>：唯一出现过的调用在 mall-api
 * {@code OrderInfoServiceImpl} 的一段从未启用的「下单分账」草稿里，整段被注释掉。
 * 字段取自那段草稿实际 set 的 5 个，加上服务端读取的 {@code subMchId}。
 * 复活分账功能时必须重新按当时的真实需求核一遍字段，并做端到端验证——
 * 没有真实流量能验证这条边。
 */
@Data
public class WxProfitSharingReq {

	/** 服务商 appid。 */
	private String appid;

	/** 子商户号（分账的出资方）。 */
	private String subMchId;

	/** 微信支付流水号。 */
	private String transactionId;

	/** 商户分账单号。 */
	private String outOrderNo;

	/** 分账接收方列表，微信协议规定是一段 JSON 字符串（不是结构化字段）。 */
	private String receivers;

}
