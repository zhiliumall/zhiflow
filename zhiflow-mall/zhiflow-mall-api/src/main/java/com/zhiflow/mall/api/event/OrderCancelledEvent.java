package com.zhiflow.mall.api.event;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 订单已取消（取消事务内发布，用户、超时、后台三条取消路径都发）。
 *
 * <p>扩展点：开源版目前没有监听方。监听请用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}，
 * 只在取消事务提交后执行、回滚则不执行。
 */
@Getter
public class OrderCancelledEvent extends ApplicationEvent {
    /**
     * 订单
     */
    private final OrderInfo orderInfo;

    private final String orderId;

    private final String orderNo;

    private final String userId;

    private final String reason;

    /**
     * 取消来源（用户、系统、后台）
     */
    private final OptSourceEnum optSource;

    private final LocalDateTime eventTime;

    public OrderCancelledEvent(Object source, OrderInfo orderInfo, OptSourceEnum optSource) {
        super(source);
        this.orderInfo = orderInfo;
        this.orderId = orderInfo.getId();
        this.orderNo = orderInfo.getOrderNo();
        this.userId = orderInfo.getUserId();
        this.reason = orderInfo.getRemark();
        this.optSource = optSource;
        this.eventTime = LocalDateTime.now();
    }
}
