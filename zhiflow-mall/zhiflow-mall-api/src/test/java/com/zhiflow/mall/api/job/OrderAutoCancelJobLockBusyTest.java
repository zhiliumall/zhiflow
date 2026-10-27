package com.zhiflow.mall.api.job;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhiflow.api.upms.TenantApi;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.lock.exception.DistributedLockException;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.upms.common.dto.TenantInfo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 10-18 复测：兜底关单与买家打开订单时触发的懒惰取消在 30 分钟整点前后可能抢同一批单的 lock:order。
 * 钉住两点：① 抢单锁失败记「锁占用跳过」、不算失败；② 截止时间多宽限 2 分钟，让买家打开订单时的 lazyCancel 先处理。
 */
@ExtendWith(MockitoExtension.class)
class OrderAutoCancelJobLockBusyTest {

    private final InMemoryLocks redis = new InMemoryLocks();
    @Mock private OrderInfoService orderInfoService;
    @Mock private TenantApi tenantApi;

    private OrderAutoCancelJob job;

    /** lambda 条件要先登记实体元数据才能生成 SQL；不依赖同批其它测试类替它初始化 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("test");
        TableInfoHelper.initTableInfo(assistant, OrderInfo.class);
    }

    @BeforeEach
    void setup() {
        job = new OrderAutoCancelJob(redis.newInstance(), orderInfoService, tenantApi);
        TenantInfo t = new TenantInfo();
        t.setId("1");
        when(tenantApi.list(anyString())).thenReturn(R.ok(List.of(t)));
    }

    @Test
    void orderLockHeldByOtherPath_isSkipped_notCountedAsFailure_runSucceeds() {
        OrderInfo a = order("a");
        OrderInfo b = order("b");
        when(orderInfoService.list(any(Wrapper.class))).thenReturn(Arrays.asList(a, b));
        doThrow(new DistributedLockException("获取分布式锁失败: lock:order:a"))
                .when(orderInfoService).orderCancelV2("a", OptSourceEnum.SYSTEM);
        doNothing().when(orderInfoService).orderCancelV2("b", OptSourceEnum.SYSTEM);

        JobResult result = job.execute();

        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("成功=1, 失败=0, 锁占用跳过=1"), result.message());
        verify(orderInfoService, times(1)).orderCancelV2("b", OptSourceEnum.SYSTEM);
    }

    @Test
    void otherExceptionsStillCountAsFailure() {
        OrderInfo a = order("a");
        when(orderInfoService.list(any(Wrapper.class))).thenReturn(List.of(a));
        doThrow(new RuntimeException("支付状态无法判定"))
                .when(orderInfoService).orderCancelV2("a", OptSourceEnum.SYSTEM);

        JobResult result = job.execute();

        org.junit.jupiter.api.Assertions.assertFalse(result.success(), result.message());
        assertTrue(result.message().contains("失败=1, 锁占用跳过=0"), result.message());
    }

    @Test
    void deadlineIncludesTwoMinuteGraceAfterOrderTimeout() {
        when(orderInfoService.list(any(Wrapper.class))).thenReturn(Collections.emptyList());
        LocalDateTime before = LocalDateTime.now();

        job.execute();

        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderInfoService).list(captor.capture());
        AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) captor.getValue();
        w.getSqlSegment(); // MyBatis-Plus 生成过一次 SQL 后，条件参数才登记进 paramNameValuePairs
        LocalDateTime deadline = w.getParamNameValuePairs().values().stream()
                .filter(LocalDateTime.class::isInstance).map(LocalDateTime.class::cast)
                .findFirst().orElseThrow();
        long expected = MallConstants.ORDER_TIME_OUT_0 + OrderAutoCancelJob.GRACE_MINUTES;
        LocalDateTime upper = before.minusMinutes(expected).plusSeconds(5);
        LocalDateTime lower = before.minusMinutes(expected).minusSeconds(5);
        assertTrue(deadline.isBefore(upper) && deadline.isAfter(lower),
                "截止时间应为 now - (超时 + 2 分钟宽限)，实际 " + deadline);
    }

    private static OrderInfo order(String id) {
        OrderInfo o = new OrderInfo();
        o.setId(id);
        o.setOrderNo("ORD-" + id);
        o.setCreateTime(LocalDateTime.now().minusHours(1));
        return o;
    }
}
