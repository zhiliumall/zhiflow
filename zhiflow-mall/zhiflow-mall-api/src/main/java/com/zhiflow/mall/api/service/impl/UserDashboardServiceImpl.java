package com.zhiflow.mall.api.service.impl;

import com.zhiflow.mall.api.dto.UserDashboardDTO;
import com.zhiflow.mall.api.mapper.UserDashboardMapper;
import com.zhiflow.mall.api.service.UserDashboardService;
import com.zhiflow.mall.common.dto.OrderStatusCountDTO;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 用户面板聚合服务实现类
 *
 * @author hugy
 */
@Slf4j
@Service
public class UserDashboardServiceImpl implements UserDashboardService {

    private final UserDashboardMapper userDashboardMapper;

    /**
     * 有界连接预算执行器（来自配置类）
     * 用于并行 DB 聚合查询，限制同时借用 DB 连接的 fan-out 任务数，避免连接池过订阅
     */
    private final ExecutorService dbFanoutExecutor;

    public UserDashboardServiceImpl(
            UserDashboardMapper userDashboardMapper,
            @Qualifier("dbFanoutExecutor") ExecutorService dbFanoutExecutor) {
        this.userDashboardMapper = userDashboardMapper;
        this.dbFanoutExecutor = dbFanoutExecutor;
    }

    @Override
    public UserDashboardDTO getUserDashboard(String userId) {
        UserDashboardDTO dashboardDTO = new UserDashboardDTO();
        try {
            // ========== 第一阶段：并行查询所有独立数据 ==========

            // 并行任务1：获取用户基本信息
            CompletableFuture<UserDashboardDTO.UserInfo> userInfoFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return userDashboardMapper.getUserInfo(userId);
                } catch (Exception e) {
                    log.error("查询用户基本信息失败: userId={}", userId, e);
                    return null;
                }
            }, dbFanoutExecutor);

            // 并行任务2：获取订单统计信息(单行 map,数字状态码作键)
            CompletableFuture<Map<String, Object>> orderCountFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return userDashboardMapper.getOrderCountAll(userId);
                } catch (Exception e) {
                    log.error("查询订单统计信息失败: userId={}", userId, e);
                    return null;
                }
            }, dbFanoutExecutor);

            // 等待所有并行任务完成（3s 超时兜底，超时走既有 catch 降级为默认值，避免 DB 抖动时尾延迟无上界）
            CompletableFuture.allOf(userInfoFuture, orderCountFuture).get(3, TimeUnit.SECONDS);

            // ========== 第二阶段：组装结果 ==========

            UserDashboardDTO.UserInfo userInfo = userInfoFuture.get();
            dashboardDTO.setUserInfo(userInfo != null ? userInfo : new UserDashboardDTO.UserInfo());

            // 处理订单统计信息:查询失败(null)时给空 map,前端据此回退散装 countAll
            Map<String, Object> countMap = orderCountFuture.get();
            if (countMap != null) {
                dashboardDTO.setOrderCountAll(toOrderCountMap(countMap));
            } else {
                dashboardDTO.setOrderCountAll(new LinkedHashMap<>());
            }

        } catch (Exception e) {
            log.error("获取用户面板数据失败: userId={}", userId, e);
            // 返回默认值避免前端报错
            dashboardDTO.setUserInfo(new UserDashboardDTO.UserInfo());
            dashboardDTO.setOrderCountAll(new LinkedHashMap<>());
        }
        return dashboardDTO;
    }

    /**
     * 数据库单行统计 -> 状态码 map,键集与散装 GET /orderinfo/countAll 完全一致。
     * OrderStatusCountDTO 的 map 构造函数负责 BigDecimal/字符串到 Long 的宽容转换与缺键补零。
     */
    private static @NonNull Map<String, Long> toOrderCountMap(Map<String, Object> countMap) {
        OrderStatusCountDTO countDTO = new OrderStatusCountDTO(countMap);
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("0", countDTO.getPendingPayment());
        result.put("1", countDTO.getPendingDelivery());
        result.put("2", countDTO.getPendingReceipt());
        result.put("3", countDTO.getCompleted());
        return result;
    }
}