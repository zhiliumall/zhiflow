package com.zhiflow.mall.common.trade.statemachine.payorder;

/**
 * 支付单状态。存储码 = 枚举名（pay_order.status）。
 *
 * <p>库里若出现任何未知码，映射成 {@link #LEGACY_INVALID}，状态机对其一律拒绝（人工处理）。
 */
public enum PayOrderState {

	CREATED, PAYING, PAID, FAILED, CANCELLED, LEGACY_INVALID;

	public static PayOrderState of(String status) {
		if (status == null || status.isBlank()) {
			return LEGACY_INVALID;
		}
		for (PayOrderState s : values()) {
			if (s != LEGACY_INVALID && s.name().equals(status.trim())) {
				return s;
			}
		}
		return LEGACY_INVALID;
	}

	/** 存储码（LEGACY_INVALID 没有存储码，不会被写入）。 */
	public String code() {
		if (this == LEGACY_INVALID) {
			throw new IllegalStateException("LEGACY_INVALID 不是可写入的支付单状态");
		}
		return name();
	}
}
