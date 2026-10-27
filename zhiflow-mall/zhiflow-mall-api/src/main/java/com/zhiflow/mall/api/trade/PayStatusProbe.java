package com.zhiflow.mall.api.trade;

import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.api.payment.ChannelCapabilityResolver;
import com.zhiflow.mall.api.payment.PayOrderService;
import com.zhiflow.mall.api.payment.PaymentService;
import com.zhiflow.mall.api.payment.dto.PaymentQueryResult;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.PayOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 支付结果查询的异步探查。
 *
 * <p>{@code probe=true} 时接口只投一个探查任务并立即返回当前状态——<b>用户请求不等渠道 HTTP</b>。任务：
 * <ol>
 *   <li>去重：同一张支付单 5 秒内只投一次（Redis {@code SET NX}，多实例也成立）；</li>
 *   <li>渠道限流：每渠道一个令牌桶，速率取渠道能力矩阵 {@code queryQps}；拿不到令牌就丢弃，前端下次再投；</li>
 *   <li>工作线程查渠道：查到已付走回调九步的 ⑦–⑩（{@link PaymentService#confirmPayment}，与回调、补单同一条路）；
 *       查不通记下 UNKNOWN 供页面显示「确认中」。</li>
 * </ol>
 * 队列满 / 线程忙直接丢弃（探查是加速手段，不是入账的唯一路径：回调与补单任务仍在）。
 */
@Slf4j
@Component
public class PayStatusProbe implements DisposableBean {

	static final String DEDUPE_KEY = "pay:probe:";

	/** 最近一次探查结论（PAID / FAILED / PAYING / NOT_PAID / UNKNOWN），30 秒有效，给页面判「确认中」。 */
	static final String RESULT_KEY = "pay:probe:result:";

	private final StringRedisTemplate redis;

	private final PaymentService paymentService;

	private final PayOrderService payOrderService;

	private final ChannelCapabilityResolver capabilities;

	private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

	private final ThreadPoolExecutor executor;

	public PayStatusProbe(StringRedisTemplate redis, @Lazy PaymentService paymentService, PayOrderService payOrderService,
			ChannelCapabilityResolver capabilities) {
		this.redis = redis;
		this.paymentService = paymentService;
		this.payOrderService = payOrderService;
		this.capabilities = capabilities;
		AtomicInteger n = new AtomicInteger();
		this.executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200), r -> {
			Thread t = new Thread(r, "pay-status-probe-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		}, new ThreadPoolExecutor.DiscardPolicy());
	}

	/**
	 * 投一个探查任务。
	 *
	 * @param payOrder 要探查的支付单
	 * @return true = 已投递；false = 5 秒内投过 / 渠道限流 / 渠道不支持查单
	 */
	public boolean submit(OrderInfo order, PayOrder payOrder) {
		String channel = payOrder.getChannelCode();
		if (channel == null || !capabilities.of(channel).query()) {
			return false;
		}
		String subject = payOrder.getPayNo();
		Boolean first;
		try {
			first = redis.opsForValue().setIfAbsent(DEDUPE_KEY + subject, "1", 5, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			log.warn("支付结果探查去重失败，本次不投 subject={}", subject, e);
			return false;
		}
		if (!Boolean.TRUE.equals(first)) {
			return false;
		}
		if (!bucket(channel).tryAcquire()) {
			log.info("支付结果探查：渠道 {} 限流，丢弃 subject={}", channel, subject);
			return false;
		}
		String tenantId = order.getTenantId();
		executor.execute(() -> {
			try {
				TenantContextHolder.setTenantId(tenantId);
				run(order, payOrder);
			}
			catch (Exception e) {
				log.warn("支付结果探查异常 subject={}", subject, e);
			}
			finally {
				TenantContextHolder.clear();
			}
		});
		return true;
	}

	/** 最近一次探查结论（30 秒内），没有返回 null。 */
	public String lastResult(OrderInfo order, PayOrder payOrder) {
		if (payOrder == null) {
			return null;
		}
		try {
			return redis.opsForValue().get(RESULT_KEY + payOrder.getPayNo());
		}
		catch (Exception e) {
			return null;
		}
	}

	void run(OrderInfo order, PayOrder payOrder) {
		PaymentQueryResult q = paymentService.query(payOrder.getPayNo());
		remember(payOrder.getPayNo(), q.getState() == null ? "UNKNOWN" : q.getState().name());
		if (q.isPaid()) {
			PaymentService.ConfirmResult c = paymentService.confirmPayment(payOrder.getPayNo(), null, null, null, "PROBE");
			log.info("支付结果探查：支付单 {} 渠道已付，确认收款 {}", payOrder.getPayNo(), c.outcome());
		}
		else if (q.getState() == PaymentQueryResult.State.FAILED && "PAYING".equals(payOrder.getStatus())) {
			payOrderService.markFailed(payOrder.getPayNo(), q.getRawMessage());
		}
	}

	private void remember(String subject, String state) {
		try {
			redis.opsForValue().set(RESULT_KEY + subject, state, 30, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			log.debug("记录探查结论失败 subject={}", subject, e);
		}
	}

	TokenBucket bucket(String channel) {
		int qps = Math.max(1, capabilities.of(channel).queryQps());
		return buckets.compute(channel, (k, b) -> b == null || b.capacity != qps ? new TokenBucket(qps) : b);
	}

	@Override
	public void destroy() {
		executor.shutdownNow();
	}

	/** 每秒补满 capacity 个令牌的简单令牌桶（单实例内限速；mall-api 生产为单实例）。 */
	static final class TokenBucket {

		final int capacity;

		private double tokens;

		private long lastNanos;

		TokenBucket(int capacity) {
			this.capacity = capacity;
			this.tokens = capacity;
			this.lastNanos = System.nanoTime();
		}

		synchronized boolean tryAcquire() {
			long now = System.nanoTime();
			tokens = Math.min(capacity, tokens + (now - lastNanos) / 1_000_000_000.0 * capacity);
			lastNanos = now;
			if (tokens >= 1) {
				tokens -= 1;
				return true;
			}
			return false;
		}
	}
}
