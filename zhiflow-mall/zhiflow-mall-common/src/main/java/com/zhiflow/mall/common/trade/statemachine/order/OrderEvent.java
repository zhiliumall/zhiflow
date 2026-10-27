package com.zhiflow.mall.common.trade.statemachine.order;

/** 订单事件。 */
public enum OrderEvent {
	/** O2：支付成功 */
	PAY_SUCCESS,
	/** O10 */
	SHIP,
	/** O12 确认收货 */
	RECEIVE,
	/** O5 取消 / 超时 */
	CANCEL,
	/** LEGACY_INVALID 的后台人工处理（选一个合法状态 + 填原因） */
	MANUAL_FIX
}
