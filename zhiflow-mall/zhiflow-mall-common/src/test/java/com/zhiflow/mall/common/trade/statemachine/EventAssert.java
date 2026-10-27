package com.zhiflow.mall.common.trade.statemachine;

import com.zhiflow.mall.common.trade.statemachine.order.OrderTransitionedEvent;

/** 事件比较：重放事件的发生时刻取流水时刻，与原事件的 now() 不同，比较时去掉。 */
public final class EventAssert {

	private EventAssert() {
	}

	public static OrderTransitionedEvent withoutTime(OrderTransitionedEvent e) {
		return new OrderTransitionedEvent(e.eventId(), e.eventType(), e.version(), e.tenantId(), null, e.bizId(), e.orderNo(),
				e.payNo(), e.from(), e.to(), e.event(), e.ruleId(), e.operatorType(), e.operatorId(),
				e.amountMinor(), e.currency(), e.traceId());
	}

	/** 同上，再去掉重放拿不到的字段（流水里没记的）。 */
	public static OrderTransitionedEvent comparable(OrderTransitionedEvent e, boolean dropOperatorType, boolean dropAmount,
			boolean dropTrace) {
		return new OrderTransitionedEvent(e.eventId(), e.eventType(), e.version(), e.tenantId(), null, e.bizId(), e.orderNo(),
				e.payNo(), e.from(), e.to(), e.event(), e.ruleId(), dropOperatorType ? null : e.operatorType(),
				e.operatorId(), dropAmount ? null : e.amountMinor(), e.currency(), dropTrace ? null : e.traceId());
	}
}
