package com.zhiflow.mall.api.event;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderItem;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 订单已创建（下单事务内发布）。
 *
 * <p>扩展点：开源版目前没有监听方。要在下单后做事（通知、统计等），用
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 监听，只在下单事务提交后执行、回滚则不执行。
 */
@Getter
public class OrderCreatedEvent extends ApplicationEvent {
    private final OrderInfo orderInfo;
    private final String orderId;
    private final String orderNo;
    private final String userId;
    private final String shopId;
    private final String tenantId;
    private final LocalDateTime eventTime;
    /**
     * 待付款超时自动取消的时长（分钟）。
     */
    private final long orderTimeOut;
    /**
     * 本单已入库的订单行，监听方直接用，不必回查 order_item；为 null 表示发事件方没带，需要时按 orderId 回查。
     */
    private final List<OrderItem> orderItems;

    public OrderCreatedEvent(Object source, OrderInfo orderInfo, long orderTimeOut) {
        this(source, orderInfo, orderTimeOut, null);
    }

    public OrderCreatedEvent(Object source, OrderInfo orderInfo, long orderTimeOut, List<OrderItem> orderItems) {
        super(source);
        this.orderInfo = orderInfo;
        this.orderId = orderInfo.getId();
        this.orderNo = orderInfo.getOrderNo();
        this.userId = orderInfo.getUserId();
        this.shopId = orderInfo.getShopId();
        this.tenantId = orderInfo.getTenantId();
        this.eventTime = orderInfo.getCreateTime() != null ? orderInfo.getCreateTime() : LocalDateTime.now();
        this.orderTimeOut = orderTimeOut;
        // 拷一份列表，事件发出后调用方再增删不影响监听方；订单行对象本身共享，提交后无人再改。
        // 不用 List.copyOf：它遇到 null 元素会抛，而这里在下单事务里，抛了整单回滚
        this.orderItems = orderItems == null ? null : Collections.unmodifiableList(new ArrayList<>(orderItems));
    }
}
