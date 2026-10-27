package com.zhiflow.mall.api.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.api.upms.TenantApi;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.mall.common.enums.OrderInfoEnum;
import com.zhiflow.upms.common.dto.TenantInfo;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单自动收货定时任务
 * <p>
 * 扫描已发货超过 N 天（ORDER_TIME_OUT_2）仍为"待收货"状态的订单，自动确认收货。
 * 替代原先基于消息队列延迟投递的方案（延迟消息的最大延迟级别有限，无法满足 7 天延迟需求）。
 * <p>
 * 触发：{@code @Scheduled}，cron 配 {@code mall.job.order-auto-receive.cron}（默认每 30 分钟）；多实例靠 {@link JobLock} 互斥。
 */
@Slf4j
@Component("orderAutoReceiveJob")
@AllArgsConstructor
public class OrderAutoReceiveJob {

    static final String LOCK_NAME = "OrderAutoReceive";

    private final JobLock jobLock;
    private final StringRedisTemplate stringRedisTemplate;
    private final OrderInfoService orderInfoService;
    private final TenantApi tenantApi;

    @Scheduled(cron = "${mall.job.order-auto-receive.cron:0 */30 * * * *}")
    public JobResult execute() {
        return jobLock.runExclusive(LOCK_NAME, this::run);
    }

    private JobResult run() {
        try {
            // SchemaInterceptor 要求 tenantId 设置才切 schema，否则查的是默认 schema（无业务数据）。多租户必须按租户逐个扫。
            List<TenantInfo> tenants = tenantApi.list(SecurityConstants.FROM_IN).getData();
            if (tenants == null || tenants.isEmpty()) {
                return JobResult.ok("无租户可扫描");
            }

            LocalDateTime deadline = LocalDateTime.now().minusDays(MallConstants.ORDER_TIME_OUT_2);
            int totalScanned = 0;
            int totalSuccess = 0;
            int totalSkip = 0;
            int tenantFail = 0;

            for (TenantInfo tenant : tenants) {
                try {
                    TenantContextHolder.setTenantId(tenant.getId());

                    List<OrderInfo> orders = orderInfoService.list(
                            Wrappers.<OrderInfo>lambdaQuery()
                                    .eq(OrderInfo::getStatus, OrderInfoEnum.STATUS_2.getValue())
                                    .le(OrderInfo::getUpdateTime, deadline)
                                    .last("LIMIT 200")
                    );

                    if (orders.isEmpty()) {
                        log.debug("无需要自动收货的订单");
                        continue;
                    }

                    log.info("发现{}笔待自动收货订单", orders.size());

                    totalScanned += orders.size();
                    for (OrderInfo order : orders) {
                        try {
                            orderInfoService.orderReceive(order, OptSourceEnum.SYSTEM);
                            // 清理对应的 Redis 倒计时 key（如果还存在）
                            String redisKey = order.getTenantId() + ":" + CacheConstants.MALL_ORDER_KEY_STATUS_2 + "::" + order.getId();
                            stringRedisTemplate.delete(redisKey);
                            totalSuccess++;
                            log.info("订单自动收货成功: orderId={}, orderNo={}", order.getId(), order.getOrderNo());
                        } catch (Exception e) {
                            totalSkip++;
                            log.error("订单自动收货失败: orderId={}, orderNo={}, error={}", order.getId(), order.getOrderNo(), e.getMessage(), e);
                        }
                    }
                } catch (Exception e) {
                    tenantFail++;
                    log.error("订单自动收货任务租户级执行失败: tenantId={}", tenant.getId(), e);
                } finally {
                    TenantContextHolder.clear();
                }
            }

            String msg = String.format("扫描=%d, 成功=%d, 失败=%d, 租户失败=%d", totalScanned, totalSuccess, totalSkip, tenantFail);
            log.info("订单自动收货任务完成: {}", msg);
            // 支付状态机 v1.2 阶段 0 · 0.10：任一单笔 / 租户失败，本轮报失败并计数（此前租户失败连计数都没有）
            return JobOutcome.of("orderAutoReceiveJob", totalSkip, tenantFail, msg);
        } catch (Exception e) {
            log.error("订单自动收货任务执行失败", e);
            return JobResult.failed(e.getMessage());
        }
    }
}
