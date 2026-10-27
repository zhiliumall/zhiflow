package com.zhiflow.mall.common.trade.statemachine.order;

/**
 * 订单状态（v1.2 第四章，沿用 v1.0 5.1 的存储码，不改库里已有数据）。
 *
 * <p>订单状态是 {@code (status, is_pay)} 元组：
 * <pre>
 *   待付款 PENDING_PAY     status 为空（或历史上的 '0'）, is_pay '0'
 *   待发货 PENDING_SHIP    '1',  '1'
 *   待收货 PENDING_RECEIVE '2',  '1'
 *   已完成 COMPLETED       '3',  '1'
 *   已取消 CANCELLED       '5',  '0'   未付款时取消 / 超时
 * </pre>
 * 表外组合（如 is_pay='1' 而 status 为空或为 '5'）一律 {@link #LEGACY_INVALID}：
 * 不抛异常，只允许后台「人工处理」一个事件。
 */
public enum OrderState {

	PENDING_PAY(null, "0"),
	PENDING_SHIP("1", "1"),
	PENDING_RECEIVE("2", "1"),
	COMPLETED("3", "1"),
	CANCELLED("5", "0"),
	LEGACY_INVALID(null, null);

	/** 写库用的 status 码（待付款写 NULL，沿用「待付款 = 空」的现有约定）。 */
	private final String statusCode;

	private final String isPayCode;

	OrderState(String statusCode, String isPayCode) {
		this.statusCode = statusCode;
		this.isPayCode = isPayCode;
	}

	public String statusCode() {
		return statusCode;
	}

	public String isPayCode() {
		return isPayCode;
	}

	public boolean paid() {
		return "1".equals(isPayCode);
	}

	/** 终态：已取消。 */
	public boolean terminal() {
		return this == CANCELLED;
	}

	/**
	 * 从库里的元组还原状态。{@code status='0'} 与空值同义，照待付款认。
	 */
	public static OrderState of(String status, String isPay) {
		String s = status == null || status.isBlank() ? null : status.trim();
		if ("0".equals(isPay)) {
			if (s == null || "0".equals(s)) {
				return PENDING_PAY;
			}
			return "5".equals(s) ? CANCELLED : LEGACY_INVALID;
		}
		if ("1".equals(isPay)) {
			if (s == null) {
				return LEGACY_INVALID;
			}
			return switch (s) {
				case "1" -> PENDING_SHIP;
				case "2" -> PENDING_RECEIVE;
				case "3" -> COMPLETED;
				default -> LEGACY_INVALID;
			};
		}
		return LEGACY_INVALID;
	}
}
