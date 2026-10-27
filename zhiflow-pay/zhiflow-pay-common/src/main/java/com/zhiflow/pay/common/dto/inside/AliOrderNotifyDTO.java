package com.zhiflow.pay.common.dto.inside;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 支付宝（国内）异步通知<b>验签通过后</b>的解析结果。
 *
 * <p>pay-admin 已用 easysdk {@code verifyNotify} 验签，并核对 {@code app_id}、{@code seller_id}
 * 与本租户收款配置一致；{@code trade_status} 由调用方按渠道语义判定（只有
 * {@code TRADE_SUCCESS} / {@code TRADE_FINISHED} 算已付）。
 */
@Data
public class AliOrderNotifyDTO {

	/** 交易状态 TRADE_SUCCESS / TRADE_FINISHED / WAIT_BUYER_PAY / TRADE_CLOSED。 */
	private String tradeStatus;

	/** 商户订单号（= 支付单号）。 */
	private String outTradeNo;

	/** 支付宝交易号。 */
	private String tradeNo;

	/** 交易金额，单位<b>元</b>。 */
	private BigDecimal totalAmount;

	/** 交易付款时间 yyyy-MM-dd HH:mm:ss，可能为空。 */
	private String gmtPayment;

	/** 通知 id，同一笔交易的重复通知相同，用作幂等键。 */
	private String notifyId;
}
