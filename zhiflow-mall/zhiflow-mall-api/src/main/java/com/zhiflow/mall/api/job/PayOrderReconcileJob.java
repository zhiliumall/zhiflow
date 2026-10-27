package com.zhiflow.mall.api.job;

import com.zhiflow.api.upms.TenantApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.api.mapper.PayOrderMapper;
import com.zhiflow.mall.api.payment.ChannelCapabilityResolver;
import com.zhiflow.mall.api.payment.PayOrderService;
import com.zhiflow.mall.api.payment.PaymentService;
import com.zhiflow.mall.api.payment.dto.PaymentQueryResult;
import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.upms.common.dto.TenantInfo;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付单补单任务（所有走支付单模型的渠道共用：微信、支付宝）。
 *
 * <p>扫两类单：PAYING 超过 5 分钟的；本地已关（CANCELLED）但渠道没关单、仍可能被扣款的（channel_closed = false）。
 * 节奏：建单 2 小时内每 5 分钟一次，之后每小时一次，到渠道最大可付窗口为止——窗口取渠道能力矩阵
 * （{@link ChannelCapabilityResolver}：未声明的渠道按保守口径 7 天，可通过配置覆盖）。
 * 锁外查渠道：查到已付走 {@link PaymentService#confirmPayment}（P4 / P7 / P9 + 入账，与回调同一条路）；
 * 渠道明确失败且仍是 PAYING 走 P5；查不通 / 未付原样留着下一轮。
 *
 * <p>触发：{@code @Scheduled}，cron 配 {@code mall.job.pay-order-reconcile.cron}（默认每 5 分钟）；多实例靠 {@link JobLock} 互斥。
 */
@Slf4j
@Component("payOrderReconcileJob")
@AllArgsConstructor
public class PayOrderReconcileJob {

	static final String LOCK_NAME = "PayOrderReconcile";

	/** PAYING 多久之后才开始补单（给回调留时间）。 */
	static final Duration FIRST_CHECK_AFTER = Duration.ofMinutes(5);

	/** 高频期：建单 2 小时内每轮都查，之后每小时只在第一个 5 分钟档查一次。 */
	static final Duration DENSE_PERIOD = Duration.ofHours(2);

	private static final int BATCH_LIMIT = 200;

	private static final long TIME_BUDGET_MILLIS = 8 * 60 * 1000L;

	private final JobLock jobLock;

	private final PayOrderMapper payOrderMapper;

	private final PayOrderService payOrderService;

	private final PaymentService paymentService;

	private final TenantApi tenantApi;

	private final ChannelCapabilityResolver capabilities;

	@Scheduled(cron = "${mall.job.pay-order-reconcile.cron:0 */5 * * * *}")
	public JobResult execute() {
		return jobLock.runExclusive(LOCK_NAME, this::run);
	}

	private JobResult run() {
		int scanned = 0;
		int paid = 0;
		int failedMarked = 0;
		int failed = 0;
		int tenantFailed = 0;
		try {
			List<TenantInfo> tenants = tenantApi.list(SecurityConstants.FROM_IN).getData();
			if (tenants == null || tenants.isEmpty()) {
				return JobResult.ok("无租户可扫描");
			}
			LocalDateTime now = LocalDateTime.now();
			// SQL 粗筛取所有渠道里最长的窗口，逐单再按各自渠道的窗口细筛
			Duration longest = capabilities.longestPayWindow();
			long deadline = System.currentTimeMillis() + TIME_BUDGET_MILLIS;
			for (TenantInfo tenant : tenants) {
				if (System.currentTimeMillis() > deadline) {
					break;
				}
				try {
					TenantContextHolder.setTenantId(tenant.getId());
					List<PayOrder> candidates = payOrderMapper.selectReconcileCandidates(now.minus(longest),
							now.minus(FIRST_CHECK_AFTER), BATCH_LIMIT);
					for (PayOrder p : candidates) {
						if (!withinPayWindow(p, now) || !dueThisRound(p, now)) {
							continue;
						}
						scanned++;
						try {
							Outcome o = reconcileOne(p);
							paid += o == Outcome.PAID ? 1 : 0;
							failedMarked += o == Outcome.MARKED_FAILED ? 1 : 0;
							failed += o == Outcome.ERROR ? 1 : 0;
						}
						catch (Exception e) {
							failed++;
							log.error("补单：支付单 {} 处理异常", p.getPayNo(), e);
						}
					}
				}
				catch (Exception e) {
					tenantFailed++;
					log.error("补单：租户 {} 处理异常", tenant.getId(), e);
				}
				finally {
					TenantContextHolder.clear();
				}
			}
			String msg = String.format("支付单补单完成：扫描 %d，补入账 %d，置失败 %d，失败 %d，租户失败 %d", scanned, paid,
					failedMarked, failed, tenantFailed);
			log.info(msg);
			return JobOutcome.of("payOrderReconcileJob", failed, tenantFailed, msg);
		}
		catch (Exception e) {
			log.error("支付单补单任务异常", e);
			return JobResult.failed("任务异常：" + e.getMessage());
		}
	}

	enum Outcome { PAID, MARKED_FAILED, WAITING, ERROR }

	/** 超过该渠道「关单后仍可能收款」的窗口就不再查。 */
	boolean withinPayWindow(PayOrder p, LocalDateTime now) {
		return p.getCreateTime() == null
				|| p.getCreateTime().isAfter(now.minus(capabilities.of(p.getChannelCode()).maxPayWindow()));
	}

	/** 建单 2 小时内每轮都查；之后每小时只在第一个 5 分钟档查一次（任务每 5 分钟一轮）。 */
	static boolean dueThisRound(PayOrder p, LocalDateTime now) {
		if (p.getCreateTime() == null) {
			return true;
		}
		if (Duration.between(p.getCreateTime(), now).compareTo(DENSE_PERIOD) < 0) {
			return true;
		}
		return now.getMinute() < 5;
	}

	/** 锁外查渠道 → 已付走确认收款（与回调同一条路）；明确失败且仍 PAYING → P5。 */
	Outcome reconcileOne(PayOrder p) {
		PaymentQueryResult q = paymentService.query(p.getPayNo());
		if (q.isPaid()) {
			PaymentService.ConfirmResult c = paymentService.confirmPayment(p.getPayNo(), null, null, null, "RECONCILE");
			if (c.ack()) {
				log.warn("补单：支付单 {} 渠道已付、回调未达，已按 {} 处理（{}）", p.getPayNo(), c.outcome(), c.event());
				return Outcome.PAID;
			}
			log.error("补单：支付单 {} 渠道已付但确认收款未完成：{} {}", p.getPayNo(), c.outcome(), c.detail());
			return c.outcome() == PaymentService.ConfirmOutcome.QUERY_UNKNOWN ? Outcome.WAITING : Outcome.ERROR;
		}
		if (q.getState() == PaymentQueryResult.State.FAILED && "PAYING".equals(p.getStatus())) {
			return payOrderService.markFailed(p.getPayNo(), q.getRawMessage()) ? Outcome.MARKED_FAILED : Outcome.WAITING;
		}
		return Outcome.WAITING;
	}
}
