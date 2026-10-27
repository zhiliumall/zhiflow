package com.zhiflow.mall.api.event;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderItem;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单已支付（支付入账事务内发布）。
 *
 * <p>扩展点：开源版目前没有监听方。监听请用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}，
 * 只在入账事务提交后执行、回滚则不执行。
 */
@Getter
public class OrderPaidEvent extends ApplicationEvent {
    private final OrderInfo orderInfo;

    private final List<OrderItem> orderItems;

    private final String orderId;

    private final String orderNo;

    private final String userId;

    private final BigDecimal paymentPrice;

    private final LocalDateTime paymentTime;

    private final String paymentType;

    private final LocalDateTime eventTime;

    public OrderPaidEvent(Object source, OrderInfo orderInfo, List<OrderItem> orderItems) {
        super(source);
        this.orderInfo = orderInfo;
        this.orderItems = orderItems;
        this.orderId = orderInfo.getId();
        this.orderNo = orderInfo.getOrderNo();
        this.userId = orderInfo.getUserId();
        this.paymentPrice = orderInfo.getPaymentPrice();
        this.paymentTime = orderInfo.getPaymentTime();
        this.paymentType = orderInfo.getPaymentType();
        this.eventTime = orderInfo.getPaymentTime() != null ? orderInfo.getPaymentTime() : LocalDateTime.now();
    }
}
