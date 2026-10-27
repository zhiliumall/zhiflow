package com.zhiflow.mall.api.job;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.zhiflow.api.upms.TenantApi;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.upms.common.dto.TenantInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderAutoCancelJobTest {

    private final InMemoryLocks redis = new InMemoryLocks();
    @Mock private OrderInfoService orderInfoService;
    @Mock private TenantApi tenantApi;

    private OrderAutoCancelJob job;

    @BeforeEach
    void setup() {
        job = new OrderAutoCancelJob(redis.newInstance(), orderInfoService, tenantApi);
    }

    @Test
    void skips_when_lock_held() {
        redis.holdByAnotherInstance(OrderAutoCancelJob.LOCK_NAME);

        JobResult result = job.execute();

        assertTrue(result.success());
        verify(tenantApi, never()).list(anyString());
        verify(orderInfoService, never()).list(any(Wrapper.class));
        verify(orderInfoService, never()).orderCancelV2(anyString(), any());
    }

    @Test
    void cancels_all_stuck_orders_and_releases_lock() {
        mockSingleTenant();

        OrderInfo a = makeOrder("a", "ORD-A");
        OrderInfo b = makeOrder("b", "ORD-B");
        when(orderInfoService.list(any(Wrapper.class))).thenReturn(Arrays.asList(a, b));
        doNothing().when(orderInfoService).orderCancelV2(anyString(), eq(OptSourceEnum.SYSTEM));

        JobResult result = job.execute();

        assertTrue(result.success());
        assertTrue(result.message().contains("成功=2"));
        verify(orderInfoService, times(1)).orderCancelV2("a", OptSourceEnum.SYSTEM);
        verify(orderInfoService, times(1)).orderCancelV2("b", OptSourceEnum.SYSTEM);
        assertTrue(redis.isFree(OrderAutoCancelJob.LOCK_NAME));
    }

    @Test
    void partial_failure_counted_in_msg_but_does_not_stop_others_and_fails_the_run() {
        mockSingleTenant();

        OrderInfo a = makeOrder("a", "ORD-A");
        OrderInfo b = makeOrder("b", "ORD-B");
        when(orderInfoService.list(any(Wrapper.class))).thenReturn(Arrays.asList(a, b));

        doThrow(new RuntimeException("lock fail")).when(orderInfoService).orderCancelV2("a", OptSourceEnum.SYSTEM);
        doNothing().when(orderInfoService).orderCancelV2("b", OptSourceEnum.SYSTEM);

        JobResult result = job.execute();

        // 支付状态机 v1.2 阶段 0 · 0.10（反例）：单笔失败不中断其余订单，但本轮报失败（修前报成功）
        org.junit.jupiter.api.Assertions.assertFalse(result.success());
        assertTrue(result.message().contains("成功=1"));
        assertTrue(result.message().contains("失败=1"));
        verify(orderInfoService, times(1)).orderCancelV2("b", OptSourceEnum.SYSTEM);
    }

    @Test
    void empty_result_returns_success_with_no_op_message() {
        mockSingleTenant();
        when(orderInfoService.list(any(Wrapper.class))).thenReturn(Collections.emptyList());

        JobResult result = job.execute();

        assertTrue(result.success());
        // No orders scanned → aggregated counters are all zero
        assertTrue(result.message().contains("扫描=0"));
        verify(orderInfoService, never()).orderCancelV2(anyString(), any());
    }

    private void mockSingleTenant() {
        TenantInfo t = new TenantInfo();
        t.setId("1");
        List<TenantInfo> tenants = Collections.singletonList(t);
        when(tenantApi.list(anyString())).thenReturn(R.ok(tenants));
    }

    private OrderInfo makeOrder(String id, String no) {
        OrderInfo o = new OrderInfo();
        o.setId(id);
        o.setOrderNo(no);
        o.setCreateTime(LocalDateTime.now().minusMinutes(45));
        return o;
    }
}
