package com.zhiflow.mall.common.trade.statemachine.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 订单跳转事件（标准格式，version=1）。
 *
 * <p>{@code eventId} = 状态流水 id。以 Spring 事件在进程内发布（见 {@code OrderStatePort#publish}）。
 */
public record OrderTransitionedEvent(
		String eventId,
		String eventType,
		int version,
		String tenantId,
		OffsetDateTime occurredAt,
		String bizId,
		String orderNo,
		String payNo,
		String from,
		String to,
		String event,
		String ruleId,
		String operatorType,
		String operatorId,
		Long amountMinor,
		String currency,
		String traceId) {

	public static final String TYPE = "OrderTransitioned";

	public static Long amountMinor(BigDecimal yuan) {
		return yuan == null ? null : yuan.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
	}
}
