package com.zhiflow.mall.admin.statistics;

import lombok.Data;

@Data
public class HomepageUserAggregate {
    private Long todayNewUserCount;
    private Long monthNewUserCount;
    private Long totalUserCount;
}
