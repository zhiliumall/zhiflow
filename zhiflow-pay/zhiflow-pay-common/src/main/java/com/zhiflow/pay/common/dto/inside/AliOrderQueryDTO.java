package com.zhiflow.pay.common.dto.inside;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 支付宝（国内）<b>查单</b>结果的跨服务投影，取代 SDK 的
 * {@code com.alipay.easysdk.payment.common.models.AlipayTradeQueryResponse}（39 个 public 字段）。
 *
 * <p>4 个活调用点（mall-api {@code checkAlipayPaymentStatus} ×2、mall-admin
 * {@code orderCancelCashier}、{@code ShopDepositController} 的支付宝回调复核）逐字同构，
 * 读 {@code code / tradeStatus / totalAmount / sendPayDate / tradeNo}。
 * {@code msg / subCode / subMsg / outTradeNo} 属「错误描述 / 单号」两类，0 读但保留（排障与对账）。
 *
 * <p>⚠️ 金额口径与微信相反：支付宝 SDK 的 {@code totalAmount} 本来就是<b>元</b>制十进制字符串，
 * 而调用方今天写的正是 {@code new BigDecimal(resp.getTotalAmount())} 再 {@code compareTo}。
 * 所以这里用 {@code BigDecimal}（proto 侧 {@code Decimal}）是<b>恒等</b>转换，不是换算。
 */
@Data
public class AliOrderQueryDTO {

	/** 网关返回码，{@code 10000} 为成功。4/4 调用点判它。 */
	private String code;

	/** 网关返回描述。0 读，排障用。 */
	private String msg;

	/** 业务错误码。0 读，排障用。 */
	private String subCode;

	/** 业务错误描述。0 读，排障用。 */
	private String subMsg;

	/** 交易状态 TRADE_SUCCESS / TRADE_FINISHED / WAIT_BUYER_PAY / TRADE_CLOSED。 */
	private String tradeStatus;

	/** 交易金额，单位<b>元</b>。 */
	private BigDecimal totalAmount;

	/** 买家付款时间。 */
	private String sendPayDate;

	/** 支付宝交易号。 */
	private String tradeNo;

	/** 商户订单号。0 读但保留。 */
	private String outTradeNo;

}
