package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.mall.api.event.OrderCreatedEvent;
import com.zhiflow.mall.api.mapper.GoodsSkuMapper;
import com.zhiflow.mall.api.mapper.GoodsSkuSpecValueMapper;
import com.zhiflow.mall.api.mapper.OrderInfoMapper;
import com.zhiflow.mall.api.mapper.OrderRecordMapper;
import com.zhiflow.mall.api.service.OrderItemService;
import com.zhiflow.mall.api.service.ShoppingCartService;
import com.zhiflow.mall.api.service.UserAddressService;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.dto.PlaceOrderDTO;
import com.zhiflow.mall.common.dto.PlaceOrderSkuDTO;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderRecord;
import com.zhiflow.mall.common.entity.UserAddress;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.mall.common.enums.OrderRecordTypeEnum;
import com.zhiflow.mall.common.orderno.FixedWorkerIdAssigner;
import com.zhiflow.mall.common.orderno.NoOpOrderNoFailureSink;
import com.zhiflow.mall.common.orderno.OrderNoGenerator;
import com.zhiflow.mall.common.orderno.OrderNoMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 10-22 下单链路清理：{@code orderAddOne} 去掉不改变结果的往返，扣库存挪到写事务末尾。
 *
 * <ul>
 *   <li>建单的 CREATE 操作记录直接 INSERT（订单 id 是本事务刚分配的，不可能已有记录），不再先 COUNT；</li>
 *   <li>操作记录挪到扣库存之前：扣库存拿到 goods_sku 行锁之后，事务里只剩库存预占（开关关着不写）与提交；</li>
 *   <li>扣库存失败（库存不足）照旧抛「请重新提交」整单回滚，事件不发；</li>
 *   <li>控制器核过的收货地址随 DTO 复用，不再查第二次。</li>
 * </ul>
 *
 * <p>只 mock 接口（不 mock 具体类，避免 inline mock maker 污染同批次其它测试的类型缓存）：
 * 服务按构造器参数类型逐个装配——接口给 mock，订单号生成器 / 算价器用真对象，其余可空依赖给 null。
 */
class OrderAddOneRoundTripTest {

    private static final String ME = "u1";

    private final Map<Class<?>, Object> deps = new HashMap<>();

    private OrderInfoServiceImpl service;

    private OrderInfoMapper orderInfoMapper;

    private GoodsSkuMapper goodsSkuMapper;

    private OrderRecordMapper orderRecordMapper;

    private OrderItemService orderItemService;

    private ShoppingCartService shoppingCartService;

    private UserAddressService userAddressService;

    private ApplicationEventPublisher applicationEventPublisher;

    @BeforeEach
    void setUp() throws Exception {
        deps.put(OrderNoGenerator.class, new OrderNoGenerator(new FixedWorkerIdAssigner(0L, 0L),
                new OrderNoMetrics(new SimpleMeterRegistry()), new NoOpOrderNoFailureSink()));
        deps.put(OrderValidatorService.class, new OrderValidatorService());
        service = construct();
        orderInfoMapper = Mockito.mock(OrderInfoMapper.class);
        injectBaseMapper(service, orderInfoMapper);
        goodsSkuMapper = dep(GoodsSkuMapper.class);
        orderRecordMapper = dep(OrderRecordMapper.class);
        orderItemService = dep(OrderItemService.class);
        shoppingCartService = dep(ShoppingCartService.class);
        userAddressService = dep(UserAddressService.class);
        applicationEventPublisher = dep(ApplicationEventPublisher.class);

        when(goodsSkuMapper.listSkuInOrderSub(any(), anyList())).thenReturn(List.of(sellableSku("sku1", "spu1", "10.00")));
        when(dep(GoodsSkuSpecValueMapper.class).listGoodsSkuSpecValueBySkuIds(anyList())).thenReturn(Collections.emptyList());
        doAnswer(inv -> {
            inv.<OrderInfo>getArgument(0).setId("order-1");
            return 1;
        }).when(orderInfoMapper).insert(any(OrderInfo.class));
        when(userAddressService.getById("addr-1")).thenReturn(address("addr-1", ME));
        // 剩余库存 8（> 低库存阈值 5，不登记提交后清缓存）
        when(goodsSkuMapper.decreaseStock(any(), anyInt())).thenReturn(8);
    }

    @Test
    @DisplayName("CREATE 记录直接 INSERT（不先 COUNT）；顺序：订单 → 明细 → 购物车 → 操作记录 → 扣库存 → 事件")
    void createRecordInsertedWithoutCount_andStockDecrementIsLastWrite() throws Exception {
        OrderInfo orderInfo = service.orderAddOne(null, placeOrder(placeSku("sku1", "spu1", 2)));

        verify(orderRecordMapper, never()).selectCount(any());
        ArgumentCaptor<OrderRecord> record = ArgumentCaptor.forClass(OrderRecord.class);
        InOrder order = Mockito.inOrder(orderInfoMapper, orderItemService, shoppingCartService, orderRecordMapper,
                goodsSkuMapper, applicationEventPublisher);
        order.verify(orderInfoMapper).insert(any(OrderInfo.class));
        order.verify(orderItemService).saveBatch(anyList());
        order.verify(shoppingCartService).remove(any(Wrapper.class));
        order.verify(orderRecordMapper).insert(record.capture());
        order.verify(goodsSkuMapper).decreaseStock("sku1", 2);
        ArgumentCaptor<OrderCreatedEvent> created = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        order.verify(applicationEventPublisher).publishEvent(created.capture());
        assertEquals("order-1", record.getValue().getOrderId());
        // 订单已创建事件：带订单 id、待付款超时时长与本单订单行，监听方不必回查
        assertEquals("order-1", created.getValue().getOrderId());
        assertEquals(MallConstants.ORDER_TIME_OUT_0, created.getValue().getOrderTimeOut());
        assertEquals(1, created.getValue().getOrderItems().size());
        assertEquals(OrderRecordTypeEnum.CREATE.name(), record.getValue().getRecordType());
        assertEquals(OptSourceEnum.USER.name(), record.getValue().getRecordSource());
        // 金额仍按库里的 SKU 售价算
        assertEquals(new BigDecimal("20.00"), orderInfo.getPaymentPrice());
    }

    @Test
    @DisplayName("扣库存挪到最后后库存不足：前面的写（订单、明细、操作记录）同一事务，抛「请重新提交」整单回滚，事件不发")
    void stockShortage_stillRollsBackAfterRecordWritten() {
        when(goodsSkuMapper.decreaseStock(any(), anyInt())).thenReturn(null);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.orderAddOne(null, placeOrder(placeSku("sku1", "spu1", 1))));

        assertEquals("请重新提交", e.getMessage());
        verify(orderRecordMapper).insert(any(OrderRecord.class));
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("控制器核过的地址随 DTO 带进来：不再按 userAddressId 查库，收货信息取自该地址")
    void resolvedAddressReusedWithoutQuery() throws Exception {
        PlaceOrderDTO dto = placeOrder(placeSku("sku1", "spu1", 1));
        UserAddress a = address("addr-1", ME);
        dto.setResolvedShippingAddress(a);

        OrderInfo orderInfo = service.orderAddOne(null, dto);

        verifyNoInteractions(userAddressService);
        assertEquals("张三", orderInfo.getRecName());
        assertEquals("13800000000", orderInfo.getRecTel());
        assertEquals("广东省深圳市南山区科技园1号", orderInfo.getRecAddress());
    }

    @Test
    @DisplayName("没带核过的地址（直接调 orderAddOne）：照旧查库核验一次")
    void withoutResolvedAddressLoadsOnce() throws Exception {
        service.orderAddOne(null, placeOrder(placeSku("sku1", "spu1", 1)));

        verify(userAddressService).getById("addr-1");
    }

    @Test
    @DisplayName("SPU 软删 / 不存在（JOIN 取回 goodsSpu 为 null）：照旧拒单「参数错误」，一行都不写")
    void missingSpuRejectedBeforeAnyWrite() {
        GoodsSku orphan = sellableSku("sku1", "spu1", "10.00");
        orphan.setGoodsSpu(null);
        when(goodsSkuMapper.listSkuInOrderSub(any(), anyList())).thenReturn(List.of(orphan));
        PlaceOrderDTO dto = placeOrder(placeSku("sku1", "spu1", 1));
        dto.setResolvedShippingAddress(address("addr-1", ME));

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.orderAddOne(null, dto));

        assertInstanceOf(com.zhiflow.common.core.exception.BizException.class, e);
        assertEquals("参数错误", e.getMessage());
        verify(orderInfoMapper, never()).insert(any(OrderInfo.class));
        verifyNoInteractions(orderRecordMapper);
        verify(goodsSkuMapper, never()).decreaseStock(any(), anyInt());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private OrderInfoServiceImpl construct() throws Exception {
        Constructor<?>[] ctors = OrderInfoServiceImpl.class.getDeclaredConstructors();
        assertEquals(1, ctors.length, "OrderInfoServiceImpl 预期只有 Lombok 生成的那一个构造器");
        Constructor<?> ctor = ctors[0];
        Class<?>[] types = ctor.getParameterTypes();
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
            // 其余具体类依赖（StockHoldService、OrderStockDeduction 等）给 null：服务对它们按「关」处理
        }
        return (OrderInfoServiceImpl) ctor.newInstance(args);
    }

    @SuppressWarnings("unchecked")
    private <T> T dep(Class<T> type) {
        Object o = deps.get(type);
        if (o == null) {
            throw new AssertionError("构造器里没有 " + type.getSimpleName() + " 这个依赖，夹具已失效");
        }
        return (T) o;
    }

    /** baseMapper 是 MyBatis-Plus 基类的 protected 字段（所在层级随版本变），沿继承链反射注入。 */
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

    private static GoodsSku sellableSku(String skuId, String spuId, String price) {
        GoodsSpu spu = new GoodsSpu();
        spu.setId(spuId);
        spu.setName("N-" + spuId);
        spu.setShelf(CommonConstants.YES);
        spu.setVerifyStatus(MallConstants.GOODS_STATUS_1);
        spu.setPicUrls(new String[]{"a.jpg"});
        GoodsSku sku = new GoodsSku();
        sku.setId(skuId);
        sku.setSpuId(spuId);
        sku.setSalesPrice(new BigDecimal(price));
        sku.setStock(10);
        sku.setGoodsSpu(spu);
        return sku;
    }

    private static UserAddress address(String id, String owner) {
        UserAddress a = new UserAddress();
        a.setId(id);
        a.setUserId(owner);
        a.setUserName("张三");
        a.setTelNum("13800000000");
        a.setProvinceName("广东省");
        a.setCityName("深圳市");
        a.setCountyName("南山区");
        a.setDetailInfo("科技园1号");
        return a;
    }

    private static PlaceOrderSkuDTO placeSku(String skuId, String spuId, int quantity) {
        PlaceOrderSkuDTO s = new PlaceOrderSkuDTO();
        s.setSkuId(skuId);
        s.setSpuId(spuId);
        s.setQuantity(quantity);
        return s;
    }

    private static PlaceOrderDTO placeOrder(PlaceOrderSkuDTO... skus) {
        PlaceOrderDTO dto = new PlaceOrderDTO();
        dto.setUserId(ME);
        dto.setShopId("shop1");
        dto.setAppId("app-1");
        dto.setUserAddressId("addr-1");
        dto.setSkus(List.of(skus));
        return dto;
    }
}
