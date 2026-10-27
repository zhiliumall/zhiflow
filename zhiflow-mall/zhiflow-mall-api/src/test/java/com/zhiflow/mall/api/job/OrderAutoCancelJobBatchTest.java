package com.zhiflow.mall.api.job;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhiflow.api.upms.TenantApi;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.api.service.OrderInfoService;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 兜底关单按积压连续翻页（10-18 压测每 5 分钟只清 200 单，约 8400 单积压清了 3.5 小时）。
 *
 * <p>重点护栏：按 (create_time, id) 游标翻页，<b>一轮内每单最多调用一次 orderCancelV2</b>——
 * 每调用一次「支付状态查不通」计数就 +1，重复调用会提前打开照常取消的逃生口。
 * 判据取 MyBatis-Plus 真拼出来的 SQL 与参数。测试方法全部平铺，不用 {@code @Nested}。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderAutoCancelJobBatchTest {

    private static final long MILLI = TimeUnit.MILLISECONDS.toNanos(1);
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 18, 3, 0);

    private final InMemoryLocks redis = new InMemoryLocks();
    @Mock private OrderInfoService orderInfoService;
    @Mock private TenantApi tenantApi;

    private final AtomicLong clock = new AtomicLong(0);
    private OrderAutoCancelJob job;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("test");
        TableInfoHelper.initTableInfo(assistant, OrderInfo.class);
    }

    @BeforeEach
    void setup() {
        job = new OrderAutoCancelJob(redis.newInstance(), orderInfoService, tenantApi);
        job.setNanoTime(clock::get);
    }

    @Test
    @SuppressWarnings("unchecked")
    void secondBatchCarriesKeysetCursor_andOrdersByCreateTimeThenId() {
        tenants("1");
        List<OrderInfo> first = orders("o", 0, 200);
        when(orderInfoService.list(any(Wrapper.class))).thenReturn(first, orders("o", 200, 30));

        JobResult result = job.execute();

        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("扫描=230"), result.message());
        ArgumentCaptor<Wrapper<OrderInfo>> w = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderInfoService, times(2)).list(w.capture());

        String sql1 = w.getAllValues().get(0).getSqlSegment();
        String sql2 = w.getAllValues().get(1).getSqlSegment();
        // 对照：既有扫描条件两批都在
        for (String sql : Arrays.asList(sql1, sql2)) {
            assertTrue(sql.contains("is_pay =")
                    && sql.contains("status IS NULL") && sql.contains("create_time <="), sql);
            assertTrue(sql.contains("ORDER BY create_time ASC,id ASC"), sql);
        }
        // 第一批不带游标，第二批带 (create_time, id) 游标
        assertFalse(sql1.contains("create_time >=") || sql1.contains("id >"), sql1);
        assertTrue(sql2.contains("create_time >=") && sql2.contains("create_time >") && sql2.contains("id >"), sql2);
        Collection<Object> params = ((AbstractWrapper<OrderInfo, ?, ?>) w.getAllValues().get(1))
                .getParamNameValuePairs().values();
        OrderInfo last = first.get(first.size() - 1);
        assertTrue(params.contains(last.getCreateTime()), params.toString());
        assertTrue(params.contains(last.getId()), params.toString());
    }

    @Test
    void eachOrderCancelledAtMostOncePerRound_evenWhenItFails() {
        tenants("1");
        // 假库：按游标返回，失败单也不会被删（取消失败仍满足扫描条件）
        FakeOrderTable table = new FakeOrderTable();
        table.put("1", orders("o", 0, 450));
        when(orderInfoService.list(any(Wrapper.class))).thenAnswer(inv -> table.select(inv.getArgument(0)));
        List<String> cancelled = new ArrayList<>();
        doAnswer(inv -> {
            String id = inv.getArgument(0);
            cancelled.add(id);
            if ("o-00007".equals(id)) {
                throw new IllegalStateException("支付状态确认中");
            }
            return null;
        }).when(orderInfoService).orderCancelV2(anyString(), eq(OptSourceEnum.SYSTEM));

        JobResult result = job.execute();

        assertFalse(result.success(), result.message());
        assertTrue(result.message().contains("扫描=450, 成功=449, 失败=1"), result.message());
        verify(orderInfoService, times(1)).orderCancelV2("o-00007", OptSourceEnum.SYSTEM);
        assertEquals(450, cancelled.stream().distinct().count());
        assertEquals(450, cancelled.size());
        // 从最老的单开始清
        assertEquals("o-00000", cancelled.get(0));
    }

    @Test
    void stopsMidBatchWhenBudgetUsedUp_afterFirstRound() {
        tenants("1");
        FakeOrderTable table = new FakeOrderTable();
        table.put("1", orders("o", 0, 5000));
        when(orderInfoService.list(any(Wrapper.class))).thenAnswer(inv -> table.select(inv.getArgument(0)));
        // 每单 100ms：第一圈 200 单不受预算打断（20s），之后批内到 50s 即停
        doAnswer(inv -> {
            clock.addAndGet(100 * MILLI);
            return null;
        }).when(orderInfoService).orderCancelV2(anyString(), eq(OptSourceEnum.SYSTEM));

        JobResult result = job.execute();

        assertTrue(result.success(), result.message());
        verify(orderInfoService, times(500)).orderCancelV2(anyString(), eq(OptSourceEnum.SYSTEM));
        assertTrue(result.message().contains("扫描=500"), result.message());
        assertTrue(result.message().contains("仍有积压租户=1"), result.message());
        assertTrue(redis.isFree(OrderAutoCancelJob.LOCK_NAME));
    }

    @Test
    void bigBacklogTenantDoesNotStarveSmallTenant() {
        tenants("A", "B");
        FakeOrderTable table = new FakeOrderTable();
        table.put("A", orders("a", 0, 100_000));
        table.put("B", orders("b", 0, 230));
        List<String> sequence = new ArrayList<>();
        when(orderInfoService.list(any(Wrapper.class))).thenAnswer(inv -> {
            sequence.add(TenantContextHolder.getTenantId());
            clock.addAndGet(10_000 * MILLI);
            return table.select(inv.getArgument(0));
        });

        JobResult result = job.execute();

        assertTrue(result.success(), result.message());
        // 时钟 10 秒/批：A(10) B(20) | A(30) B(40，清空) A(50) | 50 秒到点停
        assertEquals(Arrays.asList("A", "B", "A", "B", "A"), sequence);
        verify(orderInfoService, times(230)).orderCancelV2(startsWith("b-"), eq(OptSourceEnum.SYSTEM));
        assertTrue(result.message().contains("仍有积压租户=1"), result.message());
    }

    private void tenants(String... ids) {
        List<TenantInfo> list = new ArrayList<>();
        for (String id : ids) {
            TenantInfo t = new TenantInfo();
            t.setId(id);
            list.add(t);
        }
        when(tenantApi.list(anyString())).thenReturn(R.ok(list));
    }

    /** 每 3 单共用一个下单时间，用来覆盖「同一 create_time 靠 id 区分」的游标分支。 */
    private static List<OrderInfo> orders(String prefix, int from, int count) {
        List<OrderInfo> list = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            OrderInfo o = new OrderInfo();
            o.setId(String.format("%s-%05d", prefix, i));
            o.setOrderNo("NO-" + o.getId());
            o.setCreateTime(T0.plusSeconds(i / 3));
            list.add(o);
        }
        return list;
    }

    /**
     * 只认游标的假 order_info：从 Wrapper 参数里取出上一批最后一单的 id（形如 x-00000 的字符串参数），
     * 返回它之后按 (create_time, id) 升序的 200 单。不做任何删除——取消成功与否都不影响下次返回什么，
     * 所以只要游标没生效，就会反复返回同一批。
     */
    private static final class FakeOrderTable {
        private final Map<String, List<OrderInfo>> byTenant = new HashMap<>();

        void put(String tenant, List<OrderInfo> orders) {
            byTenant.put(tenant, orders.stream()
                    .sorted(Comparator.comparing(OrderInfo::getCreateTime).thenComparing(OrderInfo::getId))
                    .collect(Collectors.toList()));
        }

        private int calls;

        @SuppressWarnings("unchecked")
        List<OrderInfo> select(Wrapper<OrderInfo> wrapper) {
            // 游标失效时实现会死循环（本用例时钟不走）：用 Error 直接打穿 job 的 catch(Exception)
            if (++calls > 1000) {
                throw new AssertionError("查询次数超过 1000，游标没生效");
            }
            // MP 的条件参数在拼 SQL 时才登记进 paramNameValuePairs
            wrapper.getSqlSegment();
            List<OrderInfo> all = byTenant.get(TenantContextHolder.getTenantId());
            Map<String, OrderInfo> byId = all.stream().collect(Collectors.toMap(OrderInfo::getId, Function.identity()));
            OrderInfo after = ((AbstractWrapper<OrderInfo, ?, ?>) wrapper).getParamNameValuePairs().values().stream()
                    .filter(v -> v instanceof String && byId.containsKey(v))
                    .map(byId::get)
                    .findFirst().orElse(null);
            int start = after == null ? 0 : all.indexOf(after) + 1;
            return new ArrayList<>(all.subList(start, Math.min(all.size(), start + OrderAutoCancelJob.BATCH_LIMIT)));
        }
    }
}
