package com.zhiflow.mall.api.payment.settlement;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.PayOrder;
import lombok.extern.slf4j.Slf4j;


/**
 * 入账处理器的找单逻辑。
 */
@Slf4j
final class OrderSettlementSupport {

    private OrderSettlementSupport() {
    }

    /**
     * 找订单：优先按支付单上记的订单 id（{@code bizId}），回退按订单号。
     *
     * <p>两条路都留着是因为 {@code order_no} 会被后台改价重签 —— 那正是支付单与订单号
     * 解耦的起因；{@code bizId} 记的是订单主键，不会变。
     */
    static OrderInfo findOrder(OrderInfoService orderInfoService, PayOrder payOrder) {
        if (payOrder.getBizId() != null && !payOrder.getBizId().isBlank()) {
            OrderInfo byId = orderInfoService.getById(payOrder.getBizId());
            if (byId != null) {
                return byId;
            }
            log.warn("按 bizId={} 找不到订单，回退按订单号 {} 查", payOrder.getBizId(), payOrder.getOrderNo());
        }
        if (payOrder.getOrderNo() == null || payOrder.getOrderNo().isBlank()) {
            return null;
        }
        return orderInfoService.getOne(Wrappers.<OrderInfo>lambdaQuery()
                .eq(OrderInfo::getOrderNo, payOrder.getOrderNo())
                .last("limit 1"));
    }
}
