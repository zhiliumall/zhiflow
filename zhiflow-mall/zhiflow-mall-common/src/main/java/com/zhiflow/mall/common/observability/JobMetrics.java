package com.zhiflow.mall.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务的运行指标。mall-admin 与 mall-api 的
 * {@code @Scheduled} 切面共用这一份命名与桶，两种装配形态口径一致。
 *
 * <ul>
 *   <li>{@code job_runs_total{job,outcome}} —— success 正常结束 / failure 任务自己报失败
 *       （抛 {@code JobFailedException}）/ error 其它异常 / skipped 分布式锁被占（上一轮还没跑完），
 *       或任务在方法体里主动整轮跳过（业务开关暂停了结算，见 {@link #markSkipped()}）</li>
 *   <li>{@code job_duration_seconds{job}} —— 单轮耗时，只出 SLO 桶（1s 5s 30s 1m 5m 15m 1h），不出分位数</li>
 * </ul>
 * job 名取 {@code 类名#方法名}，都是代码里的有限集合。
 */
public final class JobMetrics {

	public static final String M_RUNS = "job_runs_total";

	public static final String M_DURATION = "job_duration_seconds";

	public static final String SUCCESS = "success";

	public static final String FAILURE = "failure";

	public static final String ERROR = "error";

	public static final String SKIPPED = "skipped";

	static final List<String> OUTCOMES = List.of(SUCCESS, FAILURE, ERROR, SKIPPED);

	private static final Duration[] SLO = {Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30),
			Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1)};

	/** 本线程当前这一轮任务是否已被方法体标成「整轮跳过」。任务切面进出时都会清掉，见 {@link #consumeSkipped()}。 */
	private static final ThreadLocal<Boolean> SKIPPED_BY_TASK = new ThreadLocal<>();

	private JobMetrics() {
	}

	/**
	 * 任务在方法体里<b>主动整轮跳过</b>时调用（目前只有一种：业务开关暂停了结算 / 发放）。
	 * 外层任务切面（mall-admin、mall-api 的 {@code @Scheduled} 切面）据此把本轮记成
	 * {@link #SKIPPED}，而不是 success。
	 *
	 * <p>为什么不直接 return 了事：那样本轮记成 success，暂停期间看板上的「跑成功了」是假信号。
	 * 为什么不抛异常：抛出去会落成 error，被 JobFailed（{@code outcome=~"failure|error"}）当成任务故障告警，
	 * 而暂停本身已经有 AutoBrakeTriggered（P0）在报。
	 *
	 * <p>只对当前线程、当前这一轮有效；没有任务切面包裹（单测直接调用）时无副作用——下一次进切面前会被清掉。
	 */
	public static void markSkipped() {
		SKIPPED_BY_TASK.set(Boolean.TRUE);
	}

	/** 任务切面用：取出并清除本线程的「整轮跳过」标记。进入任务前调一次（清残留），返回后再调一次（取结果）。 */
	public static boolean consumeSkipped() {
		boolean skipped = Boolean.TRUE.equals(SKIPPED_BY_TASK.get());
		SKIPPED_BY_TASK.remove();
		return skipped;
	}

	public static void record(String job, String outcome, long elapsedNanos) {
		try {
			MeterRegistry r = Metrics.globalRegistry;
			runs(r, job, outcome).increment();
			duration(r, job).record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
		}
		catch (Throwable ignored) {
			// 打点失败不影响任务
		}
	}

	/** 预注册：启动时把已知任务的全部 outcome 注册为 0（「查不到」= 埋点坏了，而不是「还没跑过」）。 */
	public static void preRegister(String job) {
		try {
			MeterRegistry r = Metrics.globalRegistry;
			for (String outcome : OUTCOMES) {
				runs(r, job, outcome);
			}
			duration(r, job);
		}
		catch (Throwable ignored) {
			// 预注册失败只影响「为 0 时是否可见」
		}
	}

	private static Counter runs(MeterRegistry r, String job, String outcome) {
		return Counter.builder(M_RUNS)
				.description("定时任务运行次数（success / failure / error / skipped）")
				.tag("job", job)
				.tag("outcome", outcome)
				.register(r);
	}

	private static Timer duration(MeterRegistry r, String job) {
		return Timer.builder(M_DURATION)
				.description("定时任务单轮耗时")
				.tag("job", job)
				.serviceLevelObjectives(SLO)
				.register(r);
	}
}
