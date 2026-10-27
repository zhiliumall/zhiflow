package com.zhiflow.mall.admin.statistics;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class HomepageDailyAggregate {
    private LocalDate date;
    private long paidOrderCount;
    private BigDecimal amount;
}
