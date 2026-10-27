package com.zhiflow.mall.common.trade.statemachine.order;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.trade.statemachine.Guard;
import com.zhiflow.mall.common.trade.statemachine.Operator;
import com.zhiflow.mall.common.trade.statemachine.TransitionTable;

import static com.zhiflow.mall.common.trade.statemachine.order.OrderEvent.*;
import static com.zhiflow.mall.common.trade.statemachine.order.OrderState.*;

/**
 * 订单跳转表——<b>唯一真源</b>。
 *
 * <p>改这张表 = 改状态机：穷举测试（{@code OrderTransitionsExhaustiveTest}）从这里生成用例，
 * 表里加一条「已取消 → 待发货」，穷举测试会自动多出一个用例并因缺少流水断言失败。
 */
public final class OrderTransitions {

	private OrderTransitions() {
	}

	/** 人工处理只允许后台选一个合法状态并填原因。 */
	static final Guard<OrderInfo, OrderTransitionContext> MANUAL_FIX_GUARD = (o, c) -> {
		if (c.reason() == null || c.reason().isBlank()) {
			return "必须填写原因";
		}
		if (c.manualTarget() == null || c.manualTarget() == LEGACY_INVALID) {
			return "必须选择一个合法的目标状态";
		}
		return null;
	};

	public static final TransitionTable<OrderState, OrderEvent, OrderInfo, OrderTransitionContext> TABLE =
			TransitionTable.<OrderState, OrderEvent, OrderInfo, OrderTransitionContext>builder(OrderState.class, OrderEvent.class)
					.rule("O2").from(PENDING_PAY).on(PAY_SUCCESS).to(PENDING_SHIP)
							.by(Operator.CHANNEL, Operator.SYSTEM, Operator.USER).add()
					.rule("O5").from(PENDING_PAY).on(CANCEL).to(CANCELLED)
							.by(Operator.USER, Operator.SYSTEM, Operator.ADMIN).add()
					.rule("O10").from(PENDING_SHIP).on(SHIP).to(PENDING_RECEIVE)
							.by(Operator.ADMIN, Operator.SYSTEM).add()
					.rule("O12").from(PENDING_RECEIVE).on(RECEIVE).to(COMPLETED)
							.by(Operator.USER, Operator.SYSTEM, Operator.ADMIN).add()
					.rule("M1").from(LEGACY_INVALID).on(MANUAL_FIX)
							.to((o, c) -> c.manualTarget(), PENDING_PAY, PENDING_SHIP, PENDING_RECEIVE, COMPLETED, CANCELLED)
							.when(MANUAL_FIX_GUARD).by(Operator.ADMIN).add()
					// 重复到达：什么都不做（不计非法跳转）
					.idempotent(CANCELLED, CANCEL)
					.idempotent(COMPLETED, RECEIVE)
					.idempotent(PENDING_RECEIVE, SHIP)
					.idempotent(PENDING_SHIP, PAY_SUCCESS)
					.idempotent(PENDING_RECEIVE, PAY_SUCCESS)
					.idempotent(COMPLETED, PAY_SUCCESS)
					.build();
}
