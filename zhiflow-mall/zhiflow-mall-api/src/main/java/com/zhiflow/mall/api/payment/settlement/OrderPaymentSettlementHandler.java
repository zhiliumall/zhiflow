package com.zhiflow.mall.api.payment.settlement;

import com.zhiflow.mall.api.payment.PaymentSettlementHandler;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.enums.PayBizTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 商品订单的入账处理器 —— 把「钱到了」翻译成「订单往前走」（方案 N-1：回调验签后同步入账）。
 *
 * <p>在支付单确认收款的同一个事务、同一把订单锁里调 {@link OrderInfoService#notifyOrder}：
 * 入账失败整笔回滚（支付单也不置 PAID），回调应答失败，渠道按策略重推。
 *
 * <h2>幂等</h2>
 * 支付单状态机保证每张支付单的「确认收款」只放行一次（重复回调是 NOOP，不会再调到这里）；
 * {@code notifyOrder} 再按订单状态处理：待付款 → 入账；已取消 → 差错池 LATE_PAID；已付且流水不同 → 差错池 DUP_PAY。
 *
 * <h2>⚠️ 不要反向注入 PaymentService</h2>
 * 依赖方向必须始终是 {@code PaymentService → PaymentSettlementHandler → OrderInfoService}，
 * 反过来会形成循环引用。
 */
@Slf4j
@Component
public class OrderPaymentSettlementHandler implements PaymentSettlementHandler {

    private final OrderInfoService orderInfoService;

    public OrderPaymentSettlementHandler(OrderInfoService orderInfoService) {
        this.orderInfoService = orderInfoService;
    }

    @Override
    public String bizType() {
        return PayBizTypeEnum.ORDER.getValue();
    }

    @Override
    public void onPaid(PayOrder payOrder) {
        OrderInfo orderInfo = OrderSettlementSupport.findOrder(orderInfoService, payOrder);
        if (orderInfo == null) {
            // 钱到了却找不到订单：要人工介入。不抛异常——重推并不能让订单凭空出现
            log.error("支付已入账但找不到对应订单！payNo={} orderNo={} bizId={} —— 需人工介入",
                    payOrder.getPayNo(), payOrder.getOrderNo(), payOrder.getBizId());
            return;
        }
        orderInfo.setPaymentTime(payOrder.getPayTime() == null ? LocalDateTime.now() : payOrder.getPayTime());
        orderInfo.setTransactionId(payOrder.getChannelTxnId());
        orderInfo.setPaymentType(payOrder.getChannelCode());
        orderInfo.setChannelPaidAmount(payOrder.getAmount());
        orderInfo.setChannelPaidCurrency(payOrder.getCurrCode());
        orderInfo.setChannelPayNo(payOrder.getPayNo());
        orderInfoService.notifyOrder(orderInfo);
        log.info("订单入账完成 orderNo={} payNo={} channel={} txn={}", orderInfo.getOrderNo(), payOrder.getPayNo(),
                payOrder.getChannelCode(), payOrder.getChannelTxnId());
    }
}
