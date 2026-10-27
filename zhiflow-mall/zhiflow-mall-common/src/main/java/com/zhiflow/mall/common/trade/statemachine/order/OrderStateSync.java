package com.zhiflow.mall.common.trade.statemachine.order;

import com.zhiflow.mall.common.entity.OrderInfo;

/**
 * 订单状态字段的<b>内存</b>赋值入口（状态机包外不得调用 setStatus / setIsPay）。
 *
 * <p>两种用途，都不落库：
 * <ul>
 *   <li>{@link #apply}：状态机放行后，把目标状态同步回调用方手里的实体，供后续副作用（消息、分销、销量）读取。
 *       库里的状态已由状态机的条件更新写好；实体上的 status / isPay 是 {@code updateStrategy = NEVER}，
 *       之后即使整行 updateById 也不会带出去。</li>
 *   <li>{@link #initPendingPay}：建单初值 = 待付款（status 空、is_pay = 0）。插入不是跳转，不写流水。</li>
 * </ul>
 */
public final class OrderStateSync {

	private OrderStateSync() {
	}

	public static void apply(OrderInfo order, OrderState state) {
		order.setStatus(state.statusCode());
		order.setIsPay(state.isPayCode());
	}

	public static void initPendingPay(OrderInfo order) {
		apply(order, OrderState.PENDING_PAY);
	}
}
