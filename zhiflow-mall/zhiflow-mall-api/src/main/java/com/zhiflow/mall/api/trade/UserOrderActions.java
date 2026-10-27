package com.zhiflow.mall.api.trade;

import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.trade.statemachine.order.OrderActionResolver;
import com.zhiflow.mall.common.trade.statemachine.order.OrderState;

import java.time.LocalDateTime;

/**
 * 买家侧订单操作。
 *
 * <p>原样搬自 {@code OrderInfoController.fillUserActions}（行为不变），供订单详情 / 列表 / 支付结果查询
 * 与收银台聚合（{@code GET /aggregate/cashier}）共用同一口径。
 */
public final class UserOrderActions {

    private UserOrderActions() {
    }

    /** 买家侧操作（14.2）。 */
    public static void fill(OrderInfo o) {
        if (o == null) {
            return;
        }
        OrderState os = OrderState.of(o.getStatus(), o.getIsPay());
        LocalDateTime now = LocalDateTime.now();
        o.setActions(OrderActionResolver.resolve(o,
                OrderActionResolver.Side.USER,
                new OrderActionResolver.Facts(PayStatusService.payRemainSeconds(o, os, now)),
                now));
    }
}
