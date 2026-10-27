package com.zhiflow.mall.common.trade.statemachine.order;

import lombok.Builder;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 与状态一起、在同一条条件 UPDATE 里写的伴随列（只列状态跳转天然带出的列，别的字段不许借道）。
 * 全部可空：null = 不写。
 */
@Builder(toBuilder = true)
public record OrderPatch(
		LocalDateTime paymentTime,
		String transactionId,
		String paymentType,
		String tradeType,
		LocalDateTime cancelTime,
		LocalDateTime receiverTime,
		OffsetDateTime deliveryTime) {

	public static final OrderPatch EMPTY = OrderPatch.builder().build();
}
