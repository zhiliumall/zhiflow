package com.zhiflow.common.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 外部依赖统一出口的两个指标：
 * <ul>
 *   <li>{@code ext_calls_total{dep,op,outcome}} —— outcome：success / timeout / error / biz_reject</li>
 *   <li>{@code ext_call_duration_seconds{dep,op}} —— 只出 SLO 桶 0.1 / 0.5 / 1 / 3 / 5 / 10 秒</li>
 * </ul>
 * dep 取 wxpay / alipay / sms / oss / logistics 等固定词；op 是该依赖的操作名。
 * 两者都经基数闸（{@code [a-z0-9_]}，每个依赖最多 30 个 op），不合规记 {@code other}。
 *
 * <p><b>包装必须薄</b>：{@link #record} 原样返回结果、原样抛出原异常（类型不变），只在旁边计时计数；
 * 打点自身的任何异常都被吞掉。异常类型靠泛型透传，调用方原来的 {@code catch (WxPayException e)} 不用改。
 *
 * <p>走 {@link Metrics#globalRegistry}：调用点散落在 SDK 包装、静态工具类里，拿不到注入的注册表。
 */
public final class ExtCallMetrics {

	public static final String M_CALLS = "ext_calls_total";

	public static final String M_DURATION = "ext_call_duration_seconds";

	public static final String SUCCESS = "success";

	public static final String TIMEOUT = "timeout";

	public static final String ERROR = "error";

	public static final String BIZ_REJECT = "biz_reject";

	static final String OTHER = "other";

	private static final Duration[] SLO = {Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1),
			Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(10)};

	private static final Pattern TOKEN = Pattern.compile("^[a-z0-9_]{1,40}$");

	private static final int MAX_OPS_PER_DEP = 30;

	private static final int MAX_DEPS = 30;

	private static final Set<String> DEPS = ConcurrentHashMap.newKeySet();

	private static final Map<String, Set<String>> OPS = new ConcurrentHashMap<>();

	private ExtCallMetrics() {
	}

	/** 可抛受检异常的调用。 */
	@FunctionalInterface
	public interface Call<T, E extends Throwable> {
		T call() throws E;
	}

	/** 无返回值、可抛受检异常的调用。 */
	@FunctionalInterface
	public interface Action<E extends Throwable> {
		void run() throws E;
	}

	/** 计时计数地执行一次外部调用；正常返回记 success，异常按 {@link #classify(Throwable)} 归类后原样抛出。 */
	public static <T, E extends Throwable> T record(String dep, String op, Call<T, E> call) throws E {
		return record(dep, op, call, null, null);
	}

	/**
	 * @param resultClassifier 正常返回时的结论（如 SDK 在返回体里报失败 → {@link #BIZ_REJECT}）；null = 一律 success
	 * @param errorClassifier  抛异常时的结论（如带业务错误码的 SDK 异常 → {@link #BIZ_REJECT}）；null 或返回 null = 默认归类
	 */
	public static <T, E extends Throwable> T record(String dep, String op, Call<T, E> call,
			Function<? super T, String> resultClassifier, Function<Throwable, String> errorClassifier) throws E {
		long start = System.nanoTime();
		T result;
		try {
			result = call.call();
		}
		catch (Throwable t) {
			String outcome = null;
			if (errorClassifier != null) {
				try {
					outcome = errorClassifier.apply(t);
				}
				catch (Throwable ignored) {
					// 归类器坏了走默认归类
				}
			}
			recordOutcome(dep, op, outcome == null ? classify(t) : outcome, System.nanoTime() - start);
			throw t;
		}
		String outcome = SUCCESS;
		if (resultClassifier != null) {
			try {
				String o = resultClassifier.apply(result);
				if (o != null) {
					outcome = o;
				}
			}
			catch (Throwable ignored) {
				// 归类器坏了按 success 记
			}
		}
		recordOutcome(dep, op, outcome, System.nanoTime() - start);
		return result;
	}

	public static <E extends Throwable> void run(String dep, String op, Action<E> action) throws E {
		record(dep, op, () -> {
			action.run();
			return null;
		});
	}

	/** 直接记一次结论（调用点自己计时的场景，如 HTTP 客户端拦截器）。 */
	public static void recordOutcome(String dep, String op, String outcome, long elapsedNanos) {
		try {
			MeterRegistry r = Metrics.globalRegistry;
			String d = boundedDep(dep);
			String o = boundedOp(d, op);
			Counter.builder(M_CALLS)
					.description("外部依赖调用次数（success / timeout / error / biz_reject）")
					.tag("dep", d)
					.tag("op", o)
					.tag("outcome", normalizeOutcome(outcome))
					.register(r)
					.increment();
			Timer.builder(M_DURATION)
					.description("外部依赖调用耗时")
					.tag("dep", d)
					.tag("op", o)
					.serviceLevelObjectives(SLO)
					.register(r)
					.record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
		}
		catch (Throwable ignored) {
			// 打点失败不影响调用方
		}
	}

	/** 默认归类：异常链上有超时类异常 → timeout，其余 → error。 */
	public static String classify(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
			if (c instanceof SocketTimeoutException || c instanceof HttpTimeoutException
					|| c instanceof TimeoutException || c instanceof InterruptedIOException) {
				return TIMEOUT;
			}
			String name = c.getClass().getSimpleName();
			if (name.contains("Timeout")) {
				return TIMEOUT;
			}
		}
		return ERROR;
	}

	/** HTTP 状态码 → 结论：2xx/3xx success，4xx biz_reject，5xx error。 */
	public static String outcomeOfHttpStatus(int status) {
		if (status >= 500) {
			return ERROR;
		}
		if (status >= 400) {
			return BIZ_REJECT;
		}
		return SUCCESS;
	}

	private static String normalizeOutcome(String outcome) {
		if (SUCCESS.equals(outcome) || TIMEOUT.equals(outcome) || ERROR.equals(outcome)
				|| BIZ_REJECT.equals(outcome)) {
			return outcome;
		}
		return ERROR;
	}

	private static String boundedDep(String dep) {
		String d = normalize(dep);
		if (d == null) {
			return OTHER;
		}
		return admit(DEPS, d, MAX_DEPS);
	}

	private static String boundedOp(String dep, String op) {
		String o = normalize(op);
		if (o == null) {
			return OTHER;
		}
		return admit(OPS.computeIfAbsent(dep, k -> ConcurrentHashMap.newKeySet()), o, MAX_OPS_PER_DEP);
	}

	private static String normalize(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String t = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace('.', '_').replace(' ', '_');
		return TOKEN.matcher(t).matches() ? t : null;
	}

	private static String admit(Set<String> seen, String value, int cap) {
		if (seen.contains(value)) {
			return value;
		}
		if (seen.size() >= cap) {
			return OTHER;
		}
		seen.add(value);
		return value;
	}
}
