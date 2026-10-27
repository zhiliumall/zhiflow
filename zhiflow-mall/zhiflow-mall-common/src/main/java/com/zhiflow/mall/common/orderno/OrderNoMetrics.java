package com.zhiflow.mall.common.orderno;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 订单号生成的三个指标。暴露路径 {@code /actuator/prometheus}。
 *
 * <ul>
 *   <li>{@code orderno_clock_rollback_total{severity=tolerated|exceeded}} —— 时钟回拨次数</li>
 *   <li>{@code orderno_dup_retry_total{result=recovered|exhausted}} —— 重号重试次数与结果</li>
 *   <li>{@code orderno_generate_fail_total{reason=clock_rollback|duplicate}} —— 最终失败次数</li>
 * </ul>
 *
 * <h2>⚠️ 为什么要在构造期预注册</h2>
 *
 * Micrometer 的 {@code Counter.builder(...).register(registry)} 直到**首次命中**才让该指标
 * 出现在 {@code /actuator/prometheus}。于是「查不到」既可能是「没发生过」，也可能是
 * 「埋点根本没工作」，两者在读数上无法区分——排障时极易得出错误结论。
 *
 * <p>本类在构造期就把全部 6 个 (指标 × tag) 组合注册出来，值为 0。这样：
 * 「读数是 0」= 确实没发生；「查不到这个指标」= 埋点真的坏了。
 *
 * <p>{@code registry} 允许为 null：mall-common 是库模块，不能假定每个引用方都装了
 * actuator。缺失时全部降级为不计数，**不影响发号**。
 */
public class OrderNoMetrics {

	private static final String M_CLOCK_ROLLBACK = "orderno_clock_rollback_total";
	private static final String M_DUP_RETRY = "orderno_dup_retry_total";
	private static final String M_GENERATE_FAIL = "orderno_generate_fail_total";

	/** 回拨幅度在 Hutool 容忍区内（≤2s），已静默复用 lastTimestamp，发号仍成功。 */
	public static final String SEVERITY_TOLERATED = "tolerated";

	/** 回拨幅度超出 Hutool 容忍区（>2s），Hutool 抛异常，发号失败。 */
	public static final String SEVERITY_EXCEEDED = "exceeded";

	/** 重试一次后成功。 */
	public static final String RESULT_RECOVERED = "recovered";

	/** 重试后仍冲突，放弃。 */
	public static final String RESULT_EXHAUSTED = "exhausted";

	public static final String REASON_CLOCK_ROLLBACK = "clock_rollback";

	public static final String REASON_DUPLICATE = "duplicate";

	private final MeterRegistry registry;

	public OrderNoMetrics(MeterRegistry registry) {
		this.registry = registry;
		if (registry != null) {
			// 预注册：让「读数为 0」与「埋点没工作」可区分，见类注释
			clockRollbackCounter(SEVERITY_TOLERATED);
			clockRollbackCounter(SEVERITY_EXCEEDED);
			dupRetryCounter(RESULT_RECOVERED);
			dupRetryCounter(RESULT_EXHAUSTED);
			generateFailCounter(REASON_CLOCK_ROLLBACK);
			generateFailCounter(REASON_DUPLICATE);
		}
	}

	public void clockRollback(String severity) {
		if (registry != null) {
			clockRollbackCounter(severity).increment();
		}
	}

	public void dupRetry(String result) {
		if (registry != null) {
			dupRetryCounter(result).increment();
		}
	}

	public void generateFail(String reason) {
		if (registry != null) {
			generateFailCounter(reason).increment();
		}
	}

	/**
	 * ⚠️ 语义是「**被回拨影响的发号次数**」，不是「回拨事件次数」。
	 * 一次回拨会让追平水位之前的每一次发号都命中，读数远大于 1 是正常的
	 * （日志那边按水位去重，同一次回拨只打一条，两者对不上不是 bug）。
	 */
	private Counter clockRollbackCounter(String severity) {
		return Counter.builder(M_CLOCK_ROLLBACK)
				.description("被系统时钟回拨影响的订单号生成次数（非回拨事件数）")
				.tag("severity", severity)
				.register(registry);
	}

	private Counter dupRetryCounter(String result) {
		return Counter.builder(M_DUP_RETRY)
				.description("order_info_order_no_idx 冲突后的重试次数")
				.tag("result", result)
				.register(registry);
	}

	private Counter generateFailCounter(String reason) {
		return Counter.builder(M_GENERATE_FAIL)
				.description("订单号最终生成/落库失败次数")
				.tag("reason", reason)
				.register(registry);
	}
}
