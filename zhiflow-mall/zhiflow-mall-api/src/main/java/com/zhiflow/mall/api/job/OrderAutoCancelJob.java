package com.zhiflow.mall.api.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.api.upms.TenantApi;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.lock.exception.DistributedLockException;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.upms.common.dto.TenantInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 待付款订单自动取消定时任务（兜底）。
 * <p>
 * 扫描 isPay=0、status=null、create_time 早于 ORDER_TIME_OUT_0 分钟前的
 * 卡住订单，调用 orderCancelV2 取消。兜底覆盖下单后其它路径未能及时
 * 处理到的订单（遗漏、静默失败、orderCancelV2 silent return 等）。
 * <p>
 * 多租户：本仓库按 tenantId 切 PostgreSQL schema（SchemaInterceptor），
 * 必须循环所有租户 setTenantId 后再查，否则查的是默认 schema 无业务数据。
 * <p>
 * 触发：{@code @Scheduled}，cron 配 {@code mall.job.order-auto-cancel.cron}（默认每 5 分钟）；多实例靠 {@link JobLock} 互斥。
 * <p>
 * 一次执行在 {@link #BUDGET_NANOS} 预算内按租户轮转、按 (create_time, id) 游标连续翻页，
 * 不再是「每 5 分钟每租户 200 单」。
 * <p>
 * 注意：第一圈每租户一批不受预算打断，所以单次执行仍可能超过 50 秒——租户多、且每单都要查支付网关时，
 * 第一圈本身就可能跑几分钟（与改造前相同）。预算只约束第二圈及之后。
 */
@Slf4j
@Component("orderAutoCancelJob")
@RequiredArgsConstructor
public class OrderAutoCancelJob {

    static final String LOCK_NAME = "OrderAutoCancel";
    static final int BATCH_LIMIT = 200;
    /**
     * 超时 30 分钟后再宽限 2 分钟才由本任务接手：买家打开订单时 lazyCancel 会在 30 分钟整点取消，本任务兜底没人看的单。
     * 10-18 复测（当时整点由延迟消息取消）：不宽限时两条路径在同一时刻抢同一批单的 lock:order，刷满错误日志。
     */
    static final long GRACE_MINUTES = 2;
    /**
     * 单次执行的总时间预算。10-18 压测时每 5 分钟只清 200 单，约 8400 单积压清了 3.5 小时。
     * 取 50 秒：远小于 5 分钟 cron 周期，一轮清不完的留给下一轮。
     * <b>这只对第二圈及之后成立</b>：第一圈每租户一批不受预算打断（防小租户被饿死），
     * 第一圈本身的耗时不受此约束，单次执行可能超过 50 秒（与改造前相同）。
     */
    static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(50);

    private final JobLock jobLock;
    private final OrderInfoService orderInfoService;
    private final TenantApi tenantApi;
    /** 预算计时用的单调时钟；单测可替换。 */
    private LongSupplier nanoTime = System::nanoTime;

    /** 单测固定时钟用。 */
    void setNanoTime(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    @Scheduled(cron = "${mall.job.order-auto-cancel.cron:0 */5 * * * *}")
    public JobResult execute() {
        return jobLock.runExclusive(LOCK_NAME, this::run);
    }

    private JobResult run() {
        try {
            // SchemaInterceptor 要求 tenantId 设置才切 schema，
            // 否则查的是默认 schema（无业务数据）。多租户必须按租户逐个扫。
            List<TenantInfo> tenants = tenantApi.list(SecurityConstants.FROM_IN).getData();
            if (tenants == null || tenants.isEmpty()) {
                return JobResult.ok("无租户可扫描");
            }

            long startNanos = nanoTime.getAsLong();
            long budgetEnd = startNanos + BUDGET_NANOS;
            Round round = new Round(LocalDateTime.now().minusMinutes(MallConstants.ORDER_TIME_OUT_0 + GRACE_MINUTES));

            // 第一圈：每个租户先处理一批（与改造前相同，不受预算打断，小租户不会被大积压租户饿死）；
            // 取满的租户进轮转队列，之后在预算内按批轮转，用到点为止。
            Deque<TenantCursor> backlog = new ArrayDeque<>();
            for (TenantInfo tenant : tenants) {
                TenantCursor cursor = new TenantCursor(tenant.getId());
                if (cancelBatch(cursor, round, false, budgetEnd)) {
                    backlog.addLast(cursor);
                }
            }
            while (!backlog.isEmpty() && nanoTime.getAsLong() - budgetEnd < 0) {
                TenantCursor cursor = backlog.pollFirst();
                if (cancelBatch(cursor, round, true, budgetEnd)) {
                    backlog.addLast(cursor);
                }
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - startNanos);
            String msg = String.format("扫描=%d, 成功=%d, 失败=%d, 锁占用跳过=%d, 租户失败=%d, 批次=%d, 仍有积压租户=%d, 耗时=%dms",
                    round.scanned, round.success, round.fail, round.busy, round.tenantFail, round.batches, backlog.size(), elapsedMs);
            if (backlog.isEmpty()) {
                log.info("订单自动取消任务完成: {}", msg);
            } else {
                log.warn("订单自动取消任务预算用完仍有积压，下一轮继续: {}", msg);
            }
            // 支付状态机 v1.2 阶段 0 · 0.10：任一单笔 / 租户失败，本轮报失败并计数（此前恒报成功）
            return JobOutcome.of("orderAutoCancelJob", round.fail, round.tenantFail, msg);
        } catch (Exception e) {
            log.error("订单自动取消任务执行失败", e);
            return JobResult.failed(e.getMessage());
        }
    }

    /**
     * 给一个租户处理一批：从游标之后按 (create_time, id) 升序取一批，逐单串行 orderCancelV2，游标推到本批最后处理的一单。
     *
     * <p>必须用游标而不是重复同一查询：取消失败的单仍满足扫描条件，重查会在同一轮里反复捞到它，
     * 每次都要再查一遍支付网关、白白占用预算。游标保证一轮内每单最多调用一次。
     * （「查不通」放行 10-18 起按首次查不通后的时长判，见 {@code OrderInfoServiceImpl#unknownEscapeHatchOpen}，
     * 重复调用已不会提前放行。）</p>
     *
     * @param interruptible 是否允许在批内到点就停（第一圈不打断，保证每租户至少一批——因此第一圈的耗时不受预算约束，
     *                      租户多且每单要查支付网关时，单次执行仍可能超过 50 秒）
     * @param budgetEnd     预算截止的 nanoTime；单笔取消可能要查支付网关，第二圈起批内也要看预算，否则一批 200 单就可能跑出几分钟
     * @return 本批取满或批内被预算打断 ⇒ 该租户可能还有积压
     */
    private boolean cancelBatch(TenantCursor cursor, Round round, boolean interruptible, long budgetEnd) {
        try {
            TenantContextHolder.setTenantId(cursor.tenantId);

            LambdaQueryWrapper<OrderInfo> query = Wrappers.<OrderInfo>lambdaQuery()
                    .eq(OrderInfo::getDelFlag, CommonConstants.NO)
                    .eq(OrderInfo::getIsPay, CommonConstants.NO)
                    .isNull(OrderInfo::getStatus)
                    .le(OrderInfo::getCreateTime, round.deadline);
            if (cursor.lastCreateTime != null) {
                // (create_time, id) > (ct, id) 拆成 create_time >= ct AND (create_time > ct OR id > id)，
                // 前半段能走 order_info_pending_cancel_idx (create_time) 的范围扫描。
                LocalDateTime ct = cursor.lastCreateTime;
                String lastId = cursor.lastId;
                query.ge(OrderInfo::getCreateTime, ct)
                        .and(w -> w.gt(OrderInfo::getCreateTime, ct).or().gt(OrderInfo::getId, lastId));
            }
            // 从最老的单开始清；改造前没有 ORDER BY，取到哪 200 单不确定
            query.orderByAsc(OrderInfo::getCreateTime).orderByAsc(OrderInfo::getId)
                    .last("LIMIT " + BATCH_LIMIT);
            List<OrderInfo> orders = orderInfoService.list(query);

            if (orders.isEmpty()) {
                return false;
            }
            round.batches++;
            log.info("租户 {} 发现 {} 笔超时待付款订单", cursor.tenantId, orders.size());

            int processed = 0;
            for (OrderInfo order : orders) {
                // 只在第二圈起生效；第一圈整批跑完（见 interruptible 说明）
                if (interruptible && processed > 0 && nanoTime.getAsLong() - budgetEnd >= 0) {
                    break;
                }
                processed++;
                // 不论成败游标都前进：失败的单留给下一轮，本轮不再碰
                cursor.lastCreateTime = order.getCreateTime();
                cursor.lastId = order.getId();
                try {
                    orderInfoService.orderCancelV2(order.getId(), OptSourceEnum.SYSTEM);
                    round.success++;
                    log.info("订单自动取消成功: tenantId={}, orderId={}, orderNo={}",
                            cursor.tenantId, order.getId(), order.getOrderNo());
                } catch (DistributedLockException e) {
                    // lock:order 被别的取消路径（延迟消息 / 买家取消 / 支付回调）占着：那边会处理这张单，本轮跳过，不算失败
                    round.busy++;
                    log.debug("订单自动取消跳过（订单锁被占用）: tenantId={}, orderId={}", cursor.tenantId, order.getId());
                } catch (Exception e) {
                    round.fail++;
                    log.error("订单自动取消失败: tenantId={}, orderId={}, orderNo={}",
                            cursor.tenantId, order.getId(), order.getOrderNo(), e);
                }
            }
            round.scanned += processed;
            return orders.size() >= BATCH_LIMIT || processed < orders.size();
        } catch (Exception e) {
            // 租户级兜底：一个租户失败不能打挂整轮。
            // 2026-08-14 实测：sys_tenant 有在册但无 mall schema 的租户（900000000000000002），
            // PG 对 search_path 里不存在的 schema 静默忽略、回落 public ⇒ 这里必抛
            // relation "order_info" does not exist。没有本 catch 时整轮直接失败，
            // 排序在它之后的租户一个都不会被处理（当时没出大事只因它排在最后，是运气不是设计）。
            round.tenantFail++;
            log.error("订单自动取消任务租户级执行失败: tenantId={}", cursor.tenantId, e);
            return false;
        } finally {
            TenantContextHolder.clear();
        }
    }

    /** 每个租户在本轮内的翻页游标（上一批最后处理的一单）。 */
    private static final class TenantCursor {
        final String tenantId;
        LocalDateTime lastCreateTime;
        String lastId;

        TenantCursor(String tenantId) {
            this.tenantId = tenantId;
        }
    }

    /** 一次执行内的累计计数；超时截止时间一轮只算一次，轮内新超时的单留给下一轮。 */
    private static final class Round {
        final LocalDateTime deadline;
        int scanned;
        int success;
        int fail;
        /** 订单锁被其它取消路径占用而跳过的单数（不算失败） */
        int busy;
        int tenantFail;
        int batches;

        Round(LocalDateTime deadline) {
            this.deadline = deadline;
        }
    }
}
