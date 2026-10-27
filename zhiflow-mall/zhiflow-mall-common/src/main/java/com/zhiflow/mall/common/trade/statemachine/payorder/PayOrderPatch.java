package com.zhiflow.mall.common.trade.statemachine.payorder;

import lombok.Builder;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 与状态在同一条条件 UPDATE 里写的伴随列，由状态机按规则从上下文算出（调用方不直接构造）。全部可空：null = 不写。
 *
 */
@Builder
public record PayOrderPatch(
		String channelTxnId,
		LocalDateTime payTime,
		Boolean latePaid,
		Boolean duplicatePaid,
		String failReason,
		Boolean channelClosed,
		OffsetDateTime closedTime) {
}
