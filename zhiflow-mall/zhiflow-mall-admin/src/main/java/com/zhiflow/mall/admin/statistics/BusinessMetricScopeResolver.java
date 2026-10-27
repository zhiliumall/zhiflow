package com.zhiflow.mall.admin.statistics;

import org.springframework.stereotype.Component;

/**
 * 经营指标的统计口径。开源版单店铺、后台账号不绑店铺（sys_user 无 shop_id），口径恒为整个租户。
 */
@Component
public class BusinessMetricScopeResolver {
    public BusinessMetricScope resolveCurrent() {
        return BusinessMetricScope.tenant();
    }
}
