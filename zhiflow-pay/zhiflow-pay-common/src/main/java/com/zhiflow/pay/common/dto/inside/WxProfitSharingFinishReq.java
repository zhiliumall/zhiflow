package com.zhiflow.pay.common.dto.inside;

import lombok.Data;

/**
 * 微信支付（国内）分账完结的<b>请求投影</b>，取代 SDK 的
 * {@code com.github.binarywang.wxpay.bean.profitsharing.request.ProfitSharingUnfreezeRequest}。
 * 零活调用点，理由与字段来源同 {@link WxProfitSharingReq}。
 */
@Data
public class WxProfitSharingFinishReq {

	/** 服务商 appid。 */
	private String appid;

	/** 子商户号。 */
	private String subMchId;

	/** 微信支付流水号。 */
	private String transactionId;

	/** 商户分账单号。 */
	private String outOrderNo;

	/** 分账完结描述。 */
	private String description;

}
