package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.util.SpringContextHolder;
import com.zhiflow.common.data.shardingdatabase.config.ShardingDataBaseProperties;
import com.zhiflow.mall.api.mapper.OrderInfoMapper;
import com.zhiflow.mall.api.mapper.OrderItemMapper;
import com.zhiflow.mall.api.mapper.OrderRecordMapper;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 OrderInfoServiceImpl 的「懒补偿」逻辑 (page2 / getById2)：
 * 遇到「isPay=0 && status=null && createTime > 30min ago」的卡住订单时，
 * 通过 self.orderCancelV2 就地取消。
 */
@ExtendWith(MockitoExtension.class)
class OrderInfoServiceImplLazyCancelTest {

    @BeforeAll
    static void installSpringContextHolderBeanFactory() throws Exception {
        // MyBatis-Plus ServiceImpl 在 baseMapper 调用路径上会通过 SpringContextHolder
        // 获取 sharding DataSource / properties。测试环境里把这些 bean 都换成 mock。
        org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory =
                Mockito.mock(org.springframework.beans.factory.config.ConfigurableListableBeanFactory.class);
        when(beanFactory.getBean(DataSource.class)).thenReturn(Mockito.mock(DataSource.class));
        ShardingDataBaseProperties shardingProperties = new ShardingDataBaseProperties();
        shardingProperties.setEnabled(false);
        when(beanFactory.getBean(ShardingDataBaseProperties.class)).thenReturn(shardingProperties);

        Field beanFactoryField = SpringContextHolder.class.getDeclaredField("beanFactory");
        beanFactoryField.setAccessible(true);
        beanFactoryField.set(null, beanFactory);
    }

    @Mock
    private OrderInfoMapper orderInfoMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    @Mock
    private OrderRecordMapper orderRecordMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private OrderInfoService self;

    @Spy
    @InjectMocks
    private OrderInfoServiceImpl service;

    @Test
    void page2_drops_stuck_pending_orders_and_calls_cancelV2() throws Exception {
        // baseMapper 在 ServiceImpl 内部是 protected 字段，@InjectMocks 无法直接命中。
        // 显式反射设置，确保 service.page2(...) 内部 baseMapper.selectPage2 调到 mock。
        injectBaseMapper(service, orderInfoMapper);
        // self 是 @Autowired @Lazy 注入的代理引用，懒补偿走 self.orderCancelV2 以触发 AOP。
        // 测试里通过反射把 mock 塞进去，验证调用经过 self 而不是 this。
        injectField(service, "self", self);

        OrderInfo stuck = makeOrder("stuck-1", "ORD-STUCK-1", LocalDateTime.now().minusMinutes(31));
        OrderInfo fresh = makeOrder("fresh-1", "ORD-FRESH-1", LocalDateTime.now().minusMinutes(1));

        Page<OrderInfo> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(stuck, fresh));
        page.setTotal(2);

        when(orderInfoMapper.selectPage2(any(IPage.class), any(OrderInfo.class))).thenReturn(page);

        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(stringRedisTemplate.getExpire(anyString())).thenReturn(-2L);

        // 让 self.orderCancelV2 直接空跑，专心验证调用契约和列表过滤
        doNothing().when(self).orderCancelV2(eq("stuck-1"), eq(OptSourceEnum.SYSTEM));

        IPage<OrderInfo> actual = service.page2(page, new OrderInfo());

        verify(self, times(1)).orderCancelV2("stuck-1", OptSourceEnum.SYSTEM);
        verify(self, never()).orderCancelV2(eq("fresh-1"), any());
        assertEquals(1, actual.getRecords().size(), "卡住订单应从返回列表剔除");
        assertEquals("fresh-1", actual.getRecords().get(0).getId());
    }

    @Test
    void page2_keeps_fresh_pending_orders_without_cancelling() throws Exception {
        injectBaseMapper(service, orderInfoMapper);
        injectField(service, "self", self);

        OrderInfo fresh = makeOrder("fresh-only", "ORD-FRESH-ONLY", LocalDateTime.now().minusMinutes(5));

        Page<OrderInfo> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(fresh));
        page.setTotal(1);

        when(orderInfoMapper.selectPage2(any(IPage.class), any(OrderInfo.class))).thenReturn(page);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(stringRedisTemplate.getExpire(anyString())).thenReturn(-2L);

        IPage<OrderInfo> actual = service.page2(page, new OrderInfo());

        verify(self, never()).orderCancelV2(anyString(), any());
        assertEquals(1, actual.getRecords().size(), "未超时待付款订单应保留");
        assertEquals("fresh-only", actual.getRecords().get(0).getId());
    }

    @Test
    void getById2_cancels_stuck_pending_order_and_returns_fresh_state() throws Exception {
        injectBaseMapper(service, orderInfoMapper);
        injectField(service, "self", self);
        injectField(service, "orderRecordMapper", orderRecordMapper);

        OrderInfo stuck = makeOrder("stuck-2", "ORD-STUCK-2", LocalDateTime.now().minusMinutes(45));
        OrderInfo afterCancel = makeOrder("stuck-2", "ORD-STUCK-2", LocalDateTime.now().minusMinutes(45));
        afterCancel.setStatus("5"); // STATUS_5 = 已取消

        when(orderInfoMapper.selectById2("stuck-2"))
            .thenReturn(stuck)        // 第一次：卡住状态
            .thenReturn(afterCancel); // 第二次：取消后
        when(orderRecordMapper.selectList(any())).thenReturn(Collections.emptyList());

        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(stringRedisTemplate.getExpire(anyString())).thenReturn(-2L);

        doNothing().when(self).orderCancelV2(eq("stuck-2"), eq(OptSourceEnum.SYSTEM));

        OrderInfo actual = service.getById2("stuck-2");

        verify(self, times(1)).orderCancelV2("stuck-2", OptSourceEnum.SYSTEM);
        assertEquals("5", actual.getStatus(), "返回的应该是 cancel 后重新查的版本");
    }

    @Test
    void getById2_keeps_fresh_pending_order_unchanged() throws Exception {
        injectBaseMapper(service, orderInfoMapper);
        injectField(service, "self", self);
        injectField(service, "orderRecordMapper", orderRecordMapper);

        OrderInfo fresh = makeOrder("fresh-2", "ORD-FRESH-2", LocalDateTime.now().minusMinutes(5));
        when(orderInfoMapper.selectById2("fresh-2")).thenReturn(fresh);
        when(orderRecordMapper.selectList(any())).thenReturn(Collections.emptyList());

        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(stringRedisTemplate.getExpire(anyString())).thenReturn(1500L);

        OrderInfo actual = service.getById2("fresh-2");

        verify(self, never()).orderCancelV2(anyString(), any());
        assertEquals("fresh-2", actual.getId());
    }

    private OrderInfo makeOrder(String id, String orderNo, LocalDateTime createTime) {
        OrderInfo o = new OrderInfo();
        o.setId(id);
        o.setOrderNo(orderNo);
        o.setIsPay(CommonConstants.NO);
        o.setStatus(null);
        o.setCreateTime(createTime);
        return o;
    }

    private static void injectBaseMapper(Object service, Object mapper) throws Exception {
        // baseMapper 是 MyBatis-Plus ServiceImpl 的 protected 字段，沿继承链反射注入
        Class<?> c = service.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField("baseMapper");
                f.setAccessible(true);
                f.set(service, mapper);
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException("baseMapper not found on " + service.getClass());
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " not found on " + target.getClass());
    }
}
