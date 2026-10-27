package com.zhiflow.mall.api.job;

import com.zhiflow.api.upms.TenantApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.api.mapper.GoodsSkuMapper;
import com.zhiflow.mall.api.mapper.StockHoldMapper;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.api.trade.StockHoldService;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.mall.common.trade.statemachine.order.OrderState;
import com.zhiflow.upms.common.dto.TenantInfo;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 库存预占过期兜底：每分钟扫各租户「HOLD 且已过期」（订单超时 + 5 分钟）的订单——
 * <ul>
 *   <li>订单还是待付款（取消消息丢了 / 消费失败）：走 O5 取消（含渠道确认，查不通不取消，下一轮再来），放出原因记 EXPIRED_HOLD；</li>
 *   <li>订单已取消（未付，漏放）：直接放出并回补，原因 EXPIRED_HOLD；</li>
 *   <li>订单付过款：漏确认，置 CONFIRMED，不动库存；表外组合交订单「人工处理」，不动库存。</li>
 * </ul>
 * 不直接改订单状态、不在「待付款」时动库存。指标 {@code stock_hold_expired_total{action}}。
 *
 * <p>触发：{@code @Scheduled}，cron 配 {@code mall.job.stock-hold-expiry.cron}（默认每分钟）。
 */
@Slf4j
@Component("stockHoldExpiryJob")
public class StockHoldExpiryJob {

	static final int LIMIT = 200;

	static final String LOCK_NAME = "StockHoldExpiry";

	private final StockHoldMapper mapper;

	private final StockHoldService stockHoldService;

	private final OrderInfoService orderInfoService;

	private final GoodsSkuMapper goodsSkuMapper;

	private final TransactionTemplate tx;

	private final TenantApi tenantApi;

	private final JobLock jobLock;

	public StockHoldExpiryJob(StockHoldMapper mapper, StockHoldService stockHoldService, OrderInfoService orderInfoService,
			GoodsSkuMapper goodsSkuMapper, TransactionTemplate transactionTemplate, TenantApi tenantApi,
			JobLock jobLock) {
		this.mapper = mapper;
		this.stockHoldService = stockHoldService;
		this.orderInfoService = orderInfoService;
		this.goodsSkuMapper = goodsSkuMapper;
		this.tx = transactionTemplate;
		this.tenantApi = tenantApi;
		this.jobLock = jobLock;
	}

	enum Action { CANCELLED, RELEASED, CONFIRMED, WAITING, SKIPPED, FAILED }

	/**
	 * 每个实例都会触发；任务锁保证同一时刻只有一个实例在扫（原来由单点调度派发，所以之前没有这把锁）。
	 */
	@Scheduled(cron = "${mall.job.stock-hold-expiry.cron:0 * * * * *}")
	public JobResult execute() {
		return jobLock.runExclusive(LOCK_NAME, this::scan);
	}

	JobResult scan() {
		List<TenantInfo> tenants = tenantApi.list(SecurityConstants.FROM_IN).getData();
		if (tenants == null) {
			return JobResult.ok("无租户可扫描");
		}
		int[] counts = new int[Action.values().length];
		int tenantFailed = 0;
		for (TenantInfo t : tenants) {
			try {
				TenantContextHolder.setTenantId(t.getId());
				for (String orderId : mapper.selectExpiredHoldOrders(LIMIT)) {
					counts[handle(orderId).ordinal()]++;
				}
			}
			catch (Exception e) {
				tenantFailed++;
				log.error("库存预占兜底：租户 {} 异常", t.getId(), e);
			}
			finally {
				TenantContextHolder.clear();
			}
		}
		String msg = String.format("库存预占兜底：取消 %d、放出 %d、确认 %d、待渠道确认 %d、跳过 %d、失败 %d，租户失败 %d",
				counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], tenantFailed);
		log.info(msg);
		return JobOutcome.of("stockHoldExpiryJob", counts[Action.FAILED.ordinal()], tenantFailed, msg);
	}

	Action handle(String orderId) {
		Action a;
		try {
			a = doHandle(orderId);
		}
		catch (Exception e) {
			log.error("库存预占兜底失败 orderId={}", orderId, e);
			a = Action.FAILED;
		}
		Metrics.counter("stock_hold_expired_total", "action", a.name()).increment();
		return a;
	}

	private Action doHandle(String orderId) {
		OrderInfo o = orderInfoService.getById(orderId);
		if (o == null) {
			log.warn("库存预占兜底：订单 {} 不存在，HOLD 行保留待人工核对", orderId);
			return Action.SKIPPED;
		}
		OrderState s = OrderState.of(o.getStatus(), o.getIsPay());
		switch (s) {
			case PENDING_PAY -> {
				// 取消消息丢了：走 O5（取消前查渠道；查不通不取消，HOLD 留着下一轮再来）
				try {
					stockHoldService.asExpired(() -> {
						orderInfoService.orderCancelV2(orderId, OptSourceEnum.SYSTEM);
						return null;
					});
				}
				catch (com.zhiflow.common.core.exception.BizException e) {
					// 「支付状态确认中」：查不通不取消（UNKNOWN 不是未付），下一轮再来
					log.info("库存预占兜底：订单 {} 暂不取消：{}", orderId, e.getMessage());
					return Action.WAITING;
				}
				OrderInfo after = orderInfoService.getById(orderId);
				return after != null && OrderState.of(after.getStatus(), after.getIsPay()) == OrderState.CANCELLED
						? Action.CANCELLED : Action.WAITING;
			}
			case CANCELLED -> {
				// 订单早已取消（未付）但 HOLD 没放（漏放）：放出并按放出的行回补
				Boolean done = tx.execute(st -> stockHoldService.asExpired(
						() -> stockHoldService.restoreOnCancel(orderId, goodsSkuMapper::increaseStock)));
				return Boolean.TRUE.equals(done) ? Action.RELEASED : Action.SKIPPED;
			}
			case LEGACY_INVALID -> {
				log.warn("库存预占兜底：订单 {} 状态非法（{} / {}），不动库存，交订单「人工处理」", orderId, o.getStatus(), o.getIsPay());
				return Action.SKIPPED;
			}
			default -> {
				// 付过款的（含已关闭：全额退款到账时售后收口会回补库存，这里再放就双补了）——漏确认：置 CONFIRMED
				stockHoldService.confirm(orderId);
				return Action.CONFIRMED;
			}
		}
	}
}
