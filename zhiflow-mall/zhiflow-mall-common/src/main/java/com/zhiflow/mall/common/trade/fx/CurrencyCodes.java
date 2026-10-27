package com.zhiflow.mall.common.trade.fx;

import java.util.Locale;
import java.util.Map;

/**
 * 币种码归一：ISO 4217 数字码 ↔ 字母码。
 *
 * <p>老渠道的表单与回调用数字码（{@code currCode=344}），历史上 {@code pay_order.curr_code} 也存成了 "344"；
 * v1.2 起支付域新列 {@code currency} 一律存字母码。比较两个币种前先过 {@link #alpha}。
 */
public final class CurrencyCodes {

	public static final String CNY = "CNY";

	public static final String HKD = "HKD";

	private static final Map<String, String> NUMERIC_TO_ALPHA = Map.of(
			"344", HKD, "156", CNY, "840", "USD", "702", "SGD", "901", "TWD",
			"392", "JPY", "826", "GBP", "978", "EUR", "446", "MOP");

	private CurrencyCodes() {
	}

	/** 数字码 / 字母码 / RMB → 大写字母码；空值返回 null。未知码原样大写返回。 */
	public static String alpha(String code) {
		if (code == null || code.isBlank()) {
			return null;
		}
		String c = code.trim().toUpperCase(Locale.ROOT);
		if ("RMB".equals(c)) {
			return CNY;
		}
		return NUMERIC_TO_ALPHA.getOrDefault(c, c);
	}

	/** 两个码是否同一币种（{@code "344"} 与 {@code "HKD"} 相等）。任一为空返回 false。 */
	public static boolean same(String a, String b) {
		String x = alpha(a);
		return x != null && x.equals(alpha(b));
	}
}
