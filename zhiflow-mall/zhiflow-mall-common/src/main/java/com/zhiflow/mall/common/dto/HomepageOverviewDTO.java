package com.zhiflow.mall.common.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only homepage operating snapshot. Monetary values are exact decimal strings.
 * <p>本版本没有售后退款，{@code netSales} 与 {@code grossPaidCashflow} 同值。
 */
public record HomepageOverviewDTO(String timeZone, LocalDateTime generatedAt, Scope scope,
                                  FinancialPeriod today, FinancialPeriod month, FinancialPeriod cumulative,
                                  UserMetrics users, List<DailyMetric> dailyMetrics) {
    public record Scope(String type, String shopId) {}
    public record FinancialPeriod(LocalDateTime startAt, LocalDateTime endAt, long paidOrderCount,
                                  String grossPaidCashflow, String netSales) {}
    public record UserMetrics(boolean available, Long todayNewUserCount, Long monthNewUserCount,
                              Long totalUserCount, String scope) {}
    public record DailyMetric(LocalDate date, long paidOrderCount, String grossPaidCashflow,
                              String netSales, Long newUserCount) {}
}
