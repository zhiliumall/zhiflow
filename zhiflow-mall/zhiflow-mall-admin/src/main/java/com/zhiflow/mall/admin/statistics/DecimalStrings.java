package com.zhiflow.mall.admin.statistics;

import java.math.BigDecimal;

/** Keeps money exact through the HTTP boundary without JSON floating point values. */
public final class DecimalStrings {
    private DecimalStrings() {}
    public static BigDecimal zeroIfNull(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    public static String money(BigDecimal value) {
        BigDecimal normalized = zeroIfNull(value);
        return normalized.signum() == 0 ? "0.00" : normalized.toPlainString();
    }
}
