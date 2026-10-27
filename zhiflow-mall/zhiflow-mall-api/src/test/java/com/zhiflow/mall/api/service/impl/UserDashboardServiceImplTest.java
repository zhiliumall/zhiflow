package com.zhiflow.mall.api.service.impl;

import com.zhiflow.mall.api.dto.UserDashboardDTO;
import com.zhiflow.mall.api.mapper.UserDashboardMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 聚焦 orderCountAll 的响应形状契约:必须是状态码 map(与散装 /orderinfo/countAll 一致),
 * 前端按"非空对象 map"校验,list/空 map 都会触发散装回退。
 */
@ExtendWith(MockitoExtension.class)
class UserDashboardServiceImplTest {

    @Mock
    private UserDashboardMapper userDashboardMapper;

    private ExecutorService executor;

    private UserDashboardServiceImpl service;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        service = new UserDashboardServiceImpl(userDashboardMapper, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** PG 的 SUM 经 MyBatis 落成 BigDecimal,须宽容转换成 Long,键集与散装接口一致 */
    @Test
    void orderCountAll_isStatusKeyedMapMatchingScatteredContract() {
        Map<String, Object> row = new HashMap<>();
        row.put("0", BigDecimal.ZERO);
        row.put("1", new BigDecimal("12"));
        row.put("2", 0L);
        row.put("3", BigDecimal.ZERO);
        when(userDashboardMapper.getOrderCountAll("u1")).thenReturn(row);

        UserDashboardDTO dto = service.getUserDashboard("u1");

        Map<String, Long> counts = dto.getOrderCountAll();
        assertNotNull(counts);
        assertEquals(java.util.Set.of("0", "1", "2", "3"), counts.keySet());
        assertEquals(12L, counts.get("1"));
        assertEquals(0L, counts.get("0"));
    }

    /** 统计查询失败:空 map(不伪造零值),前端据此回退散装 countAll */
    @Test
    void orderCountAll_queryFailure_yieldsEmptyMap() {
        when(userDashboardMapper.getOrderCountAll("u1")).thenThrow(new RuntimeException("db down"));

        UserDashboardDTO dto = service.getUserDashboard("u1");

        assertNotNull(dto.getOrderCountAll());
        assertTrue(dto.getOrderCountAll().isEmpty());
    }

    /** 无订单用户:SUM 行全为 null,须补零而非空 map(零值是有效数据,不应触发前端回退) */
    @Test
    void orderCountAll_userWithoutOrders_yieldsZeroFilledMap() {
        Map<String, Object> row = new HashMap<>();
        row.put("0", null);
        row.put("1", null);
        row.put("2", null);
        row.put("3", null);
        when(userDashboardMapper.getOrderCountAll("u1")).thenReturn(row);

        UserDashboardDTO dto = service.getUserDashboard("u1");

        assertEquals(0L, dto.getOrderCountAll().get("1"));
        assertEquals(4, dto.getOrderCountAll().size());
    }
}
