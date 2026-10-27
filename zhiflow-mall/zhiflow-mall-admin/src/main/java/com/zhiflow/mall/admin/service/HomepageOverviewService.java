package com.zhiflow.mall.admin.service;

import com.zhiflow.mall.admin.mapper.HomepageOverviewMapper;
import com.zhiflow.mall.admin.statistics.BusinessMetricScope;
import com.zhiflow.mall.admin.statistics.BusinessStatisticsClock;
import com.zhiflow.mall.admin.statistics.DecimalStrings;
import com.zhiflow.mall.admin.statistics.HomepageDailyAggregate;
import com.zhiflow.mall.admin.statistics.HomepageFinancialAggregate;
import com.zhiflow.mall.admin.statistics.HomepageUserAggregate;
import com.zhiflow.mall.admin.statistics.MetricWindow;
import com.zhiflow.mall.common.dto.HomepageOverviewDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HomepageOverviewService {
    private final HomepageOverviewMapper mapper;
    private final BusinessStatisticsClock clock;

    public HomepageOverviewDTO overview(BusinessMetricScope scope) {
        LocalDateTime cutoff = clock.now();
        LocalDate todayDate = cutoff.toLocalDate();
        MetricWindow today = new MetricWindow(todayDate.atStartOfDay(), todayDate.plusDays(1).atStartOfDay());
        LocalDate monthDate = todayDate.withDayOfMonth(1);
        MetricWindow month = new MetricWindow(monthDate.atStartOfDay(), monthDate.plusMonths(1).atStartOfDay());
        LocalDateTime firstEvent = mapper.selectFirstEventTime(cutoff, scope);
        MetricWindow cumulative = new MetricWindow(firstEvent == null ? cutoff.minusNanos(1) : firstEvent, cutoff);

        HomepageUserAggregate users = scope.isShop() ? null : mapper.selectUsers(today, month);
        MetricWindow dailyWindow = new MetricWindow(todayDate.minusDays(29).atStartOfDay(), todayDate.plusDays(1).atStartOfDay());
        Map<LocalDate, HomepageDailyAggregate> paid = index(mapper.selectDailyPaid(dailyWindow, scope));
        Map<LocalDate, HomepageDailyAggregate> dailyUsers = scope.isShop() ? Map.of() : index(mapper.selectDailyUsers(dailyWindow));
        List<HomepageOverviewDTO.DailyMetric> daily = java.util.stream.IntStream.range(0, 30)
            .mapToObj(i -> dailyWindow.startAt().toLocalDate().plusDays(i)).map(date -> {
                HomepageDailyAggregate p = paid.getOrDefault(date, new HomepageDailyAggregate());
                BigDecimal gross = DecimalStrings.zeroIfNull(p.getAmount());
                HomepageDailyAggregate u = dailyUsers.get(date);
                return new HomepageOverviewDTO.DailyMetric(date, p.getPaidOrderCount(), DecimalStrings.money(gross),
                    DecimalStrings.money(gross), u == null ? (scope.isShop() ? null : Long.valueOf(0L)) : Long.valueOf(u.getPaidOrderCount()));
            }).toList();

        return new HomepageOverviewDTO(clock.zone().getId(), cutoff,
            new HomepageOverviewDTO.Scope(scope.type().name(), scope.shopId()), period(today, scope), period(month, scope), period(cumulative, scope),
            scope.isShop() ? new HomepageOverviewDTO.UserMetrics(false, null, null, null, "UNAVAILABLE_FOR_SHOP")
                : new HomepageOverviewDTO.UserMetrics(true, users.getTodayNewUserCount(), users.getMonthNewUserCount(), users.getTotalUserCount(), "TENANT"), daily);
    }

    private HomepageOverviewDTO.FinancialPeriod period(MetricWindow window, BusinessMetricScope scope) {
        HomepageFinancialAggregate paid = mapper.selectPaid(window, scope);
        BigDecimal gross = DecimalStrings.zeroIfNull(paid == null ? null : paid.getAmount());
        return new HomepageOverviewDTO.FinancialPeriod(window.startAt(), window.endAt(), paid == null ? 0 : paid.getPaidOrderCount(),
            DecimalStrings.money(gross), DecimalStrings.money(gross));
    }

    private Map<LocalDate, HomepageDailyAggregate> index(List<HomepageDailyAggregate> rows) {
        if (rows == null) return Map.of();
        return rows.stream().collect(Collectors.toMap(HomepageDailyAggregate::getDate, row -> row, (a, b) -> a, HashMap::new));
    }
}
