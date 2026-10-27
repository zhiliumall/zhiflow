package com.zhiflow.mall.common.trade.statemachine.payorder;

import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.entity.PayOrderStateLog;
import com.zhiflow.mall.common.trade.statemachine.order.OrderTransitionedEvent;

/**
 * 支付单状态机的持久化端口（mall-api 的 mapper 实现；pay_order 只在 mall-api 写）。
 */
public interface PayOrderStatePort {

	/** 锁内重读（{@code SELECT … FOR UPDATE}，按 pay_no）。 */
	PayOrder loadForUpdate(String payNo);

	/**
	 * 条件更新：status 仍是锁内读到的原值才改，同时写伴随列；前后开关 PG 触发器通行证。
	 *
	 * @return 影响行数；0 = 被并发改过
	 */
	int compareAndSet(String payNo, String expectedStatus, PayOrderState to, PayOrderPatch patch);

	void appendLog(PayOrderStateLog row);

	void publish(OrderTransitionedEvent event);

	String nextId();
}
