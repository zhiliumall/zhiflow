package com.zhiflow.mall.admin.mapper;

import com.zhiflow.mall.admin.statistics.BusinessMetricScope;
import com.zhiflow.mall.admin.statistics.HomepageDailyAggregate;
import com.zhiflow.mall.admin.statistics.HomepageFinancialAggregate;
import com.zhiflow.mall.admin.statistics.HomepageUserAggregate;
import com.zhiflow.mall.admin.statistics.MetricWindow;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** Isolated homepage cash-flow aggregation SQL. */
public interface HomepageOverviewMapper {
    HomepageFinancialAggregate selectPaid(@Param("window") MetricWindow window, @Param("scope") BusinessMetricScope scope);
    List<HomepageDailyAggregate> selectDailyPaid(@Param("window") MetricWindow window, @Param("scope") BusinessMetricScope scope);
    LocalDateTime selectFirstEventTime(@Param("cutoff") LocalDateTime cutoff, @Param("scope") BusinessMetricScope scope);
    HomepageUserAggregate selectUsers(@Param("today") MetricWindow today, @Param("month") MetricWindow month);
    List<HomepageDailyAggregate> selectDailyUsers(@Param("window") MetricWindow window);
}
