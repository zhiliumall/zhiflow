package com.zhiflow.mall.common.trade.statemachine.payorder;

import com.zhiflow.mall.common.entity.PayOrder;

/**
 * 支付单状态字段的内存赋值入口（状态机包外不得调用 setStatus）。不落库。
 */
public final class PayOrderStateSync {

	private PayOrderStateSync() {
	}

	/** P1 建单初值（插入不是跳转，不写流水）。 */
	public static void initCreated(PayOrder payOrder) {
		payOrder.setStatus(PayOrderState.CREATED.code());
	}

	/** 状态机放行后把目标状态同步回调用方手里的实体。 */
	public static void apply(PayOrder payOrder, PayOrderState state) {
		payOrder.setStatus(state.code());
	}
}
