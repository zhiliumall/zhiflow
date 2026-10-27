package com.zhiflow.pay.common.dto.inside;

import lombok.Data;

/**
 * 微信支付（国内）<b>查单</b>结果的跨服务投影，取代 SDK 的
 * {@code com.github.binarywang.wxpay.bean.result.WxPayOrderQueryResult}。
 *
 * <p>3 个活调用点（mall-api {@code checkWeChatPaymentStatus} ×2、mall-admin
 * {@code orderCancelCashier}）逐字同构，读 {@code tradeState / totalFee / timeEnd /
 * transactionId / tradeType}。{@code tradeStateDesc} 与 {@code outTradeNo} 属
 * 「状态 / 单号」两类，0 读但保留。
 *
 * <p>金额口径同 {@link WxOrderNotifyDTO#getTotalFee()}：{@code Integer} 分，不换算。
 */
@Data
public class WxOrderQueryDTO {

	/** 交易状态 SUCCESS/REFUND/NOTPAY/CLOSED/…。取消前复核的核心判据。 */
	private String tradeState;

	/** 交易状态描述。0 读，排障用。 */
	private String tradeStateDesc;

	/** 订单总金额，单位分。见 {@link WxOrderNotifyDTO#getTotalFee()}。 */
	private Integer totalFee;

	/** 支付完成时间，{@code yyyyMMddHHmmss}。 */
	private String timeEnd;

	/** 微信支付流水号。 */
	private String transactionId;

	/** 交易类型。 */
	private String tradeType;

	/** 商户订单号。0 读但保留。 */
	private String outTradeNo;

}
