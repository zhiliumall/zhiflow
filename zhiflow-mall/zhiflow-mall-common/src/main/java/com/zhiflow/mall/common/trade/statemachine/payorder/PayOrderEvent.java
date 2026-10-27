package com.zhiflow.mall.common.trade.statemachine.payorder;

/**
 * 支付单事件。P1「发起支付」是建单（插入 CREATED），不是跳转，不在此列。
 *
 * <p>确认收款拆成三个事件（P4 / P7 / P9），由调用方按「支付单当前状态 + 同业务单是否已有别的 PAID 单」选择——
 * 表里每个事件各自的起点与守卫把选错的情况挡成 REJECTED，而不是静默走错分支。
 */
public enum PayOrderEvent {
	/** P2 渠道下单成功 */
	CHANNEL_ORDERED,
	/** P3 渠道拒绝下单 */
	CHANNEL_REJECTED,
	/** P4 确认收款（同业务单无其他 PAID 单） */
	PAY_CONFIRMED,
	/** P5 渠道明确失败（UNKNOWN / 处理中不算） */
	CHANNEL_FAILED,
	/** P6 订单关闭，本地关支付单 */
	ORDER_CLOSED,
	/** P7 迟到的确认收款（支付单已 FAILED / CANCELLED） */
	LATE_PAY_CONFIRMED,
	/** P9 确认收款，但同业务单已有另一张 PAID */
	DUPLICATE_PAY_CONFIRMED
}
