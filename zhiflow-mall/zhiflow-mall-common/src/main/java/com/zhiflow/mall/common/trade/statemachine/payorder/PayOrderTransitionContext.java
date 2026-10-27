package com.zhiflow.mall.common.trade.statemachine.payorder;

import com.zhiflow.mall.common.trade.statemachine.Operator;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 一次支付单跳转的上下文。
 *
 * @param operatorType    触发方
 * @param reason          原因
 * @param callbackLogId   驱动本次跳转的回调留痕 id（pay_order_state_log.callback_log_id，反查原报文）
 * @param channelTxnId    渠道流水号（P4 / P7 / P9）
 * @param payTime         渠道支付时间（P4 / P7 / P9；空则取当前时间）
 * @param otherPaidExists 同业务单是否已有另一张 PAID 支付单（P4 要求否、P9 要求是；null 视为未判定 → 两者都拒）
 * @param failReason      失败原因（P3 / P5，渠道给了才记）
 * @param channelClosed   P6：渠道是否已关单
 */
@Builder(toBuilder = true)
public record PayOrderTransitionContext(
		Operator operatorType,
		String reason,
		String callbackLogId,
		String channelTxnId,
		LocalDateTime payTime,
		Boolean otherPaidExists,
		String failReason,
		Boolean channelClosed) {

	public static PayOrderTransitionContext system(String reason) {
		return PayOrderTransitionContext.builder().operatorType(Operator.SYSTEM).reason(reason).build();
	}
}
