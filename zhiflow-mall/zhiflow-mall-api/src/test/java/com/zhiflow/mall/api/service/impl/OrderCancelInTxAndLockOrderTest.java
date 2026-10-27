package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.mall.api.event.OrderCancelledEvent;
import com.zhiflow.mall.api.mapper.GoodsSkuMapper;
import com.zhiflow.mall.api.mapper.OrderInfoMapper;
import com.zhiflow.mall.api.mapper.OrderRecordMapper;
import com.zhiflow.mall.api.mapper.StockHoldMapper;
import com.zhiflow.mall.api.payment.PayOrderService;
import com.zhiflow.mall.api.service.OrderItemService;
import com.zhiflow.mall.api.trade.StockHoldService;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderItem;
import com.zhiflow.mall.common.entity.OrderRecord;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.mall.common.trade.statemachine.Transition;
import com.zhiflow.mall.common.trade.statemachine.order.OrderEvent;
import com.zhiflow.mall.common.trade.statemachine.order.OrderState;
import com.zhiflow.mall.common.trade.statemachine.order.OrderStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 10-26 订单取消：
 * <ul>
 *   <li>CANCEL 操作记录与 {@link OrderCancelledEvent} 都发生在<b>取消事务里</b>（事件约定是 AFTER_COMMIT 监听，
 *       事务外发布会让这类监听器静默跳过）；回补失败整单回滚时两者都不发生；</li>
 *   <li>回补库存按 skuId 升序（逐项回补与库存预占放出两条路径都是），与下单扣减同一加锁顺序。</li>
 * </ul>
 *
 * <p>服务按构造器参数类型装配（同 {@link OrderAddOneRoundTripTest}）：接口给 mock；事务模板用真
 * {@link TransactionTemplate} + 记录「是否在事务里」的事务管理器；状态机 / 支付单服务 mock（支付单默认空列表 ⇒ 未付）。
 */
class OrderCancelInTxAndLockOrderTest {

    private final Map<Class<?>, Object> deps = new HashMap<>();

    /** 事务管理器 getTransaction 置 true，commit / rollback 置 false。 */
    private final boolean[] inTx = new boolean[1];

    private final List<String> seen = new ArrayList<>();

    private OrderInfoMapper orderInfoMapper;

    private GoodsSkuMapper goodsSkuMapper;

    private OrderRecordMapper orderRecordMapper;

    private OrderItemService orderItemService;

    private ApplicationEventPublisher applicationEventPublisher;

    private StockHoldMapper stockHoldMapper;

    @BeforeEach
    void setUp() {
        deps.put(TransactionTemplate.class, new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                inTx[0] = true;
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                inTx[0] = false;
                seen.add("commit");
            }

            @Override
            public void rollback(TransactionStatus status) {
                inTx[0] = false;
                seen.add("rollback");
            }
        }));
        OrderStateMachine stateMachine = Mockito.mock(OrderStateMachine.class);
        when(stateMachine.fire(anyString(), eq(OrderEvent.CANCEL), any()))
                .thenReturn(Transition.ok(OrderState.PENDING_PAY, OrderState.CANCELLED, "O5", "log-1"));
        deps.put(OrderStateMachine.class, stateMachine);
        deps.put(PayOrderService.class, Mockito.mock(PayOrderService.class));
        stockHoldMapper = Mockito.mock(StockHoldMapper.class);
    }

    @Test
    @DisplayName("无库存预占：多 SKU 按 skuId 升序逐项回补（明细按降序读出）")
    void legacyRestoreInSkuIdAscendingOrder() throws Exception {
        OrderInfoServiceImpl service = build(null);
        items(item("sku3", 1), item("sku1", 2), item("sku2", 3));

        service.orderCancelV2("o1", OptSourceEnum.SYSTEM);

        InOrder order = Mockito.inOrder(goodsSkuMapper);
        order.verify(goodsSkuMapper).increaseStock("sku1", 2);
        order.verify(goodsSkuMapper).increaseStock("sku2", 3);
        order.verify(goodsSkuMapper).increaseStock("sku3", 1);
    }

    @Test
    @DisplayName("有库存预占：放出的行按 skuId 升序回补（RETURNING 顺序不保证，这里给降序）")
    void holdReleaseRestoreInSkuIdAscendingOrder() throws Exception {
        OrderInfoServiceImpl service = build(new StockHoldService(stockHoldMapper, e -> 1L, new MockEnvironment()));
        items(item("sku1", 2), item("sku2", 3));
        when(stockHoldMapper.releaseHolds("o1", StockHoldService.REASON_CANCEL)).thenReturn(List.of(
                Map.of("skuId", "sku2", "qty", 3), Map.of("skuId", "sku1", "qty", 2)));

        service.orderCancelV2("o1", OptSourceEnum.SYSTEM);

        InOrder order = Mockito.inOrder(goodsSkuMapper);
        order.verify(goodsSkuMapper).increaseStock("sku1", 2);
        order.verify(goodsSkuMapper).increaseStock("sku2", 3);
    }

    @Test
    @DisplayName("买家取消：CANCEL 记录与取消事件都在取消事务里，提交在两者之后")
    void cancelRecordAndEventInsideTheCancelTransaction_v2() throws Exception {
        OrderInfoServiceImpl service = build(null);
        items(item("sku1", 2));
        trackRecordAndEvent();

        service.orderCancelV2("o1", OptSourceEnum.USER);

        assertEquals(List.of("CANCEL/USER inTx=true", "event inTx=true", "commit"), seen);
    }

    @Test
    @DisplayName("后台取消（orderCancel）同一套：CANCEL/SELLER 记录与事件都在取消事务里")
    void cancelRecordAndEventInsideTheCancelTransaction_v1() throws Exception {
        OrderInfoServiceImpl service = build(null);
        items(item("sku1", 2));
        trackRecordAndEvent();
        OrderInfo order = new OrderInfo();
        order.setId("o1");

        service.orderCancel(order, OptSourceEnum.SELLER);

        assertEquals(List.of("CANCEL/SELLER inTx=true", "event inTx=true", "commit"), seen);
    }

    @Test
    @DisplayName("回补中途失败：整单回滚，不写 CANCEL 记录、不发取消事件")
    void restoreFailsMidway_noRecordNoEvent() throws Exception {
        OrderInfoServiceImpl service = build(null);
        items(item("sku1", 2), item("sku2", 1));
        when(goodsSkuMapper.increaseStock(eq("sku2"), anyInt())).thenThrow(new IllegalStateException("库存表不可用"));

        assertThrows(IllegalStateException.class, () -> service.orderCancelV2("o1", OptSourceEnum.SYSTEM));

        verify(orderRecordMapper, never()).insert(any(OrderRecord.class));
        verify(applicationEventPublisher, never()).publishEvent(any(OrderCancelledEvent.class));
        assertEquals(List.of("rollback"), seen);
    }

    // ------------------------------------------------------------------ helpers

    private void trackRecordAndEvent() {
        doAnswer(inv -> {
            seen.add("event inTx=" + inTx[0]);
            return null;
        }).when(applicationEventPublisher).publishEvent(any(OrderCancelledEvent.class));
        doAnswer(inv -> {
            OrderRecord r = inv.getArgument(0);
            seen.add(r.getRecordType() + "/" + r.getRecordSource() + " inTx=" + inTx[0]);
            return 1;
        }).when(orderRecordMapper).insert(any(OrderRecord.class));
    }

    private OrderInfoServiceImpl build(StockHoldService stockHoldService) throws Exception {
        if (stockHoldService != null) {
            deps.put(StockHoldService.class, stockHoldService);
        }
        Constructor<?>[] ctors = OrderInfoServiceImpl.class.getDeclaredConstructors();
        assertEquals(1, ctors.length, "OrderInfoServiceImpl 预期只有 Lombok 生成的那一个构造器");
        Class<?>[] types = ctors[0].getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (deps.containsKey(t)) {
                args[i] = deps.get(t);
            }
            else if (t.isInterface()) {
                Object mock = Mockito.mock(t);
                deps.put(t, mock);
                args[i] = mock;
            }
            // 其余具体类依赖（OrderStockDeduction 等）给 null：服务对它们按「关」处理
        }
        OrderInfoServiceImpl service = (OrderInfoServiceImpl) ctors[0].newInstance(args);
        orderInfoMapper = Mockito.mock(OrderInfoMapper.class);
        injectBaseMapper(service, orderInfoMapper);
        goodsSkuMapper = dep(GoodsSkuMapper.class);
        orderRecordMapper = dep(OrderRecordMapper.class);
        orderItemService = dep(OrderItemService.class);
        applicationEventPublisher = dep(ApplicationEventPublisher.class);

        OrderInfo unpaid = new OrderInfo();
        unpaid.setId("o1");
        unpaid.setOrderNo("NO-o1");
        unpaid.setIsPay(CommonConstants.NO);
        when(orderInfoMapper.selectById("o1")).thenReturn(unpaid);
        return service;
    }

    @SuppressWarnings("unchecked")
    private void items(OrderItem... items) {
        when(orderItemService.list(any(Wrapper.class))).thenReturn(List.of(items));
    }

    private static OrderItem item(String skuId, int quantity) {
        OrderItem i = new OrderItem();
        i.setOrderId("o1");
        i.setSkuId(skuId);
        i.setQuantity(quantity);
        return i;
    }

    @SuppressWarnings("unchecked")
    private <T> T dep(Class<T> type) {
        Object o = deps.get(type);
        if (o == null) {
            throw new AssertionError("构造器里没有 " + type.getSimpleName() + " 这个依赖，夹具已失效");
        }
        return (T) o;
    }

    private static void injectBaseMapper(Object target, Object mapper) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("baseMapper");
                f.setAccessible(true);
                f.set(target, mapper);
                return;
            }
            catch (NoSuchFieldException ignored) {
                // 往上找
            }
        }
        throw new NoSuchFieldException("baseMapper not found on " + target.getClass());
    }
}
