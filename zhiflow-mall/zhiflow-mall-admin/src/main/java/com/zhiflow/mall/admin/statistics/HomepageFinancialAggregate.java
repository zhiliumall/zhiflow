package com.zhiflow.mall.admin.statistics;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class HomepageFinancialAggregate {
    private long paidOrderCount;
    private BigDecimal amount;
}
