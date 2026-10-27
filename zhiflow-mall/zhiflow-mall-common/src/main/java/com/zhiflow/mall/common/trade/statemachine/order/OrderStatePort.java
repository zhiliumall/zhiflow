package com.zhiflow.mall.common.trade.statemachine.order;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderStateLog;

/**
 * 订单状态机的持久化端口（mall-api / mall-admin 各用自己的 mapper 实现，同形）。
 */
public interface OrderStatePort {

	/** 锁内重读（{@code SELECT … FOR UPDATE}），不用调用方传进来的快照。 */
	OrderInfo loadForUpdate(String orderId);

	/**
	 * 条件更新：只有 (status, is_pay) 仍是锁内读到的原值才改（{@code IS NOT DISTINCT FROM}），
	 * 同时写 {@code patch} 里的伴随列。实现必须在这一条 UPDATE 前后开关 PG 触发器通行证
	 * （{@code set_config('app.state_machine','1'/'0', true)}），让通行证只覆盖这一条语句。
	 *
	 * @return 影响行数；0 = 被并发改过
	 */
	int compareAndSet(String orderId, String expectedStatus, String expectedIsPay, OrderState to, OrderPatch patch);

	void appendLog(OrderStateLog row);

	/**
	 * 发布进程内跳转事件（Spring {@code ApplicationEvent}，S-1 去 MQ 后不再有 outbox）。
	 * 需要在事务提交后才处理的监听方用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}。
	 */
	void publish(OrderTransitionedEvent event);

	String nextId();

	/** O5（未付取消）后，同一事务把该单全部商品项置 CANCELLED。 */
	void markItemsCancelled(String orderId);
}
