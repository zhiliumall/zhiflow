package com.zhiflow.mall.admin.statistics;

import java.time.LocalDateTime;

/** Half-open business-time window [startAt, endAt). */
public record MetricWindow(LocalDateTime startAt, LocalDateTime endAt) {
    public MetricWindow {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("统计时间范围必须为有效的半开区间");
        }
    }
}
