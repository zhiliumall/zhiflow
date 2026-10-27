package com.zhiflow.mall.common.trade.payexception;

/**
 * 差错类型。
 *
 * <p>只有 {@link #pooled()} 的两类（「钱到了、订单收不了」）进 pay_exception 表、在后台列表里等人工标记已处理；
 * 其余两类只打日志 + 指标 {@code pay_exception_recorded_total}，事实本来就落在回调留痕里。
 */
public enum PayExceptionType {

	/** 订单已取消后才确认到账（P7、补单任务）。 */
	LATE_PAID,

	/** 同一订单第二张支付单也付了钱（P9）。 */
	DUP_PAY,

	/** 同一支付单收到不同渠道流水号的成功（渠道侧重复扣款）。需向渠道核实，不自动退。 */
	DUP_CHARGE,

	/** 回调或回查的金额 / 币种与本地不一致。 */
	AMOUNT_MISMATCH;

	/** 是否进差错池表（后台列表、人工标记已处理）。 */
	public boolean pooled() {
		return this == LATE_PAID || this == DUP_PAY;
	}
}
