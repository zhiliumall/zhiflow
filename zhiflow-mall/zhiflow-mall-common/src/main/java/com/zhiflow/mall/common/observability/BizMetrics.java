package com.zhiflow.mall.common.observability;

import com.zhiflow.common.core.exception.BizException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 交易链路的结果指标，在领域入口打点。
 *
 * <ul>
 *   <li>{@code order_create_total{tenant,outcome}} —— 下单入口（outcome：success / biz_reject / error）</li>
 *   <li>{@code pay_callback_total{tenant,channel,outcome}} + {@code pay_callback_latency_seconds{channel}}
 *       —— 支付回调处理（outcome：ok 已应答渠道 / rejected 未应答、让渠道重推 / error 处理抛异常）</li>
 * </ul>
 *
 * <p>全部走 {@link Metrics#globalRegistry}（Spring Boot 会把 Prometheus 注册表挂上去，与状态机既有的
 * {@code pay_order_transition_total} 同一做法）：领域类不用为打点改构造器，
 * 否则会连带打挂一批 {@code @InjectMocks} 单测。
 *
 * <p><b>永不抛异常</b>：每个方法都吞掉自身的一切异常，打点坏了不能拖垮下单 / 回调。
 */
public final class BizMetrics {

	public static final String M_ORDER_CREATE = "order_create_total";

	public static final String M_PAY_CALLBACK = "pay_callback_total";

	public static final String M_PAY_CALLBACK_LATENCY = "pay_callback_latency_seconds";

	public static final String OUTCOME_SUCCESS = "success";

	public static final String OUTCOME_BIZ_REJECT = "biz_reject";

	public static final String OUTCOME_ERROR = "error";

	/** 回调：已给渠道回了成功应答（含重复回调、非成功事件的忽略）。 */
	public static final String CALLBACK_OK = "ok";

	/** 回调：没回成功应答，渠道会按自身策略重推（验签失败、核对不通过、暂不可处理）。 */
	public static final String CALLBACK_REJECTED = "rejected";

	/** 回调：处理过程抛异常。 */
	public static final String CALLBACK_ERROR = "error";

	static final List<String> ORDER_OUTCOMES = List.of(OUTCOME_SUCCESS, OUTCOME_BIZ_REJECT, OUTCOME_ERROR);

	private static final Duration[] CALLBACK_SLO = {Duration.ofMillis(100), Duration.ofMillis(250),
			Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
			Duration.ofSeconds(10)};

	private BizMetrics() {
	}

	public static void orderCreate(String tenantId, String outcome) {
		try {
			orderCreateCounter(registry(), ObservabilityTags.tenant(tenantId), outcome).increment();
		}
		catch (Throwable ignored) {
			// 打点失败不影响下单
		}
	}

	/** 异常 → 下单结论：业务拒绝（BizException / IllegalArgumentException）与系统错误分开。 */
	public static String orderOutcomeOf(Throwable t) {
		if (t == null) {
			return OUTCOME_SUCCESS;
		}
		return t instanceof BizException || t instanceof IllegalArgumentException ? OUTCOME_BIZ_REJECT : OUTCOME_ERROR;
	}

	/** 未注册的回调渠道码统一记这个值（见 {@link #callbackChannel}）。 */
	public static final String CHANNEL_UNKNOWN = "unknown";

	/**
	 * 回调渠道标签：只收已注册的渠道码。统一回调入口 {@code /paynotify/{channel}/{tenantId}} 的渠道段公网可填，
	 * 原样入标签会被刷出无限序列，且未知渠道的异常会被记成 error、拉低 P0 PaySuccessRateDrop 的成功率判据。
	 */
	public static String callbackChannel(String code, java.util.function.Predicate<String> registered) {
		try {
			return code != null && registered.test(code) ? code : CHANNEL_UNKNOWN;
		}
		catch (Throwable t) {
			return CHANNEL_UNKNOWN;
		}
	}

	public static void payCallback(String tenantId, String channel, String outcome, long elapsedNanos) {
		try {
			MeterRegistry r = registry();
			String ch = ObservabilityTags.token("channel", channel);
			Counter.builder(M_PAY_CALLBACK)
					.description("支付回调处理结果（ok 已应答渠道 / rejected 未应答 / error 异常）")
					.tag("tenant", ObservabilityTags.tenant(tenantId))
					.tag("channel", ch)
					.tag("outcome", outcome)
					.register(r)
					.increment();
			callbackTimer(r, ch).record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
		}
		catch (Throwable ignored) {
			// 打点失败不影响回调应答
		}
	}

	/**
	 * 预注册：把在册租户的下单计数先注册为 0，
	 * 让「查不到指标」只可能意味着埋点坏了，而不是「还没发生过」。
	 */
	public static void preRegister(Collection<String> tenantIds) {
		if (tenantIds == null) {
			return;
		}
		try {
			MeterRegistry r = registry();
			for (String raw : tenantIds) {
				String tenant = ObservabilityTags.tenant(raw);
				for (String outcome : ORDER_OUTCOMES) {
					orderCreateCounter(r, tenant, outcome);
				}
			}
		}
		catch (Throwable ignored) {
			// 预注册失败只影响「为 0 时是否可见」
		}
	}

	/**
	 * {@code order_info.payment_type} → 渠道标签（MallConstants.ORDER_PAYMENT_TYPE_*）。
	 * 1 微信 / 2 支付宝。
	 */
	public static String channelOfPaymentType(String paymentType) {
		if (paymentType == null) {
			return ObservabilityTags.OTHER;
		}
		return switch (paymentType.trim()) {
			case "1" -> "wxpay";
			case "2" -> "alipay";
			default -> ObservabilityTags.token("channel", paymentType);
		};
	}

	private static MeterRegistry registry() {
		return Metrics.globalRegistry;
	}

	private static Counter orderCreateCounter(MeterRegistry r, String tenant, String outcome) {
		return Counter.builder(M_ORDER_CREATE)
				.description("下单入口结果（success / biz_reject / error）")
				.tag("tenant", tenant)
				.tag("outcome", outcome)
				.register(r);
	}

	private static Timer callbackTimer(MeterRegistry r, String channel) {
		return Timer.builder(M_PAY_CALLBACK_LATENCY)
				.description("支付回调处理耗时（入口到应答）")
				.tag("channel", channel)
				.serviceLevelObjectives(CALLBACK_SLO)
				.register(r);
	}
}
