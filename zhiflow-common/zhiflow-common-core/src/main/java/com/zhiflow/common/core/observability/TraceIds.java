package com.zhiflow.common.core.observability;

import org.slf4j.MDC;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 链路标识工具：W3C {@code traceparent} 的解析/生成，以及「当前请求的 trace id 是哪个」。
 *
 * <h2>两种运行形态都要成立</h2>
 * <ul>
 *   <li><b>挂了 OTel Java Agent</b>：agent 维护真正的 span，{@code Span.current()} 有效 ——
 *       一律以它为准，否则日志里的 trace.id 与 Tempo 里的对不上；</li>
 *   <li><b>没挂 agent</b>（默认）：网关生成 {@code traceparent} 往下透传，各服务的过滤器把 trace id 放进 MDC，
 *       出站调用从 MDC 取同一个 trace id 再带下去。链路追踪没有，但「凭一个 id 捞全链路日志」成立。</li>
 * </ul>
 *
 * <p>OTel API 只经内部类 {@link Otel} 触达：本类在没有 OTel 类的 classpath 上也能加载，
 * 所有 OTel 调用都吞异常 —— 这是观测代码，绝不能反过来打断业务。
 */
public final class TraceIds {

	/** W3C Trace Context 请求头。 */
	public static final String TRACEPARENT = "traceparent";

	/** 响应头：32 位 hex trace id，前端/客服拿它反查。 */
	public static final String TRACE_ID_HEADER = "X-Trace-Id";

	public static final String MDC_TRACE_ID = "trace.id";

	public static final String MDC_SPAN_ID = "span.id";

	public static final String MDC_TENANT_ID = "tenant.id";

	public static final String MDC_USER_ID = "user.id";

	private static final String VERSION_00 = "00";

	private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";

	private static final String INVALID_SPAN_ID = "0000000000000000";

	private static final boolean OTEL_PRESENT = isPresent("io.opentelemetry.api.trace.Span");

	private TraceIds() {
	}

	/**
	 * 解析后的 {@code traceparent}。
	 *
	 * @param traceId      32 位小写 hex
	 * @param parentSpanId 16 位小写 hex（调用方那一跳的 span）
	 * @param flags        2 位 hex
	 */
	public record TraceParent(String traceId, String parentSpanId, String flags) {

		public String header() {
			return VERSION_00 + "-" + traceId + "-" + parentSpanId + "-" + flags;
		}
	}

	/**
	 * 按 W3C 规则解析；不合法一律返回 {@code null}（调用方据此重新生成，而不是沿用一个坏值）。
	 *
	 * <p>规则：{@code version-traceid-parentid-flags}，全部小写 hex；version {@code ff} 非法；
	 * trace id / parent id 全 0 非法；version {@code 00} 必须恰好 55 个字符，更高版本允许在第 55 位后以 {@code -} 追加字段。
	 */
	public static TraceParent parse(String header) {
		if (header == null) {
			return null;
		}
		String value = header.trim();
		if (value.length() < 55) {
			return null;
		}
		String version = value.substring(0, 2);
		if (!isLowerHex(version) || "ff".equals(version) || value.charAt(2) != '-' || value.charAt(35) != '-'
				|| value.charAt(52) != '-') {
			return null;
		}
		if (VERSION_00.equals(version) ? value.length() != 55 : value.length() > 55 && value.charAt(55) != '-') {
			return null;
		}
		String traceId = value.substring(3, 35);
		String spanId = value.substring(36, 52);
		String flags = value.substring(53, 55);
		if (!isValidTraceId(traceId) || !isValidSpanId(spanId) || !isLowerHex(flags)) {
			return null;
		}
		return new TraceParent(traceId, spanId, flags);
	}

	public static boolean isValidTraceId(String traceId) {
		return traceId != null && traceId.length() == 32 && isLowerHex(traceId) && !INVALID_TRACE_ID.equals(traceId);
	}

	public static boolean isValidSpanId(String spanId) {
		return spanId != null && spanId.length() == 16 && isLowerHex(spanId) && !INVALID_SPAN_ID.equals(spanId);
	}

	public static String newTraceId() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		long high;
		long low;
		do {
			high = random.nextLong();
			low = random.nextLong();
		}
		while (high == 0L && low == 0L);
		return hex16(high) + hex16(low);
	}

	public static String newSpanId() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		long id;
		do {
			id = random.nextLong();
		}
		while (id == 0L);
		return hex16(id);
	}

	/** 采样标志固定 01：尾部采样在 Alloy 做，这里不替它做决定。 */
	public static String traceparent(String traceId, String spanId) {
		return VERSION_00 + "-" + traceId + "-" + spanId + "-01";
	}

	/** OTel 当前 span 有效（= agent 在工作且处于某个 span 内）。 */
	public static boolean otelSpanValid() {
		return OTEL_PRESENT && Otel.valid();
	}

	/**
	 * 当前请求的 trace id：OTel 当前 span 优先，其次 MDC；都没有返回 {@code null}。
	 */
	public static String currentTraceId() {
		if (OTEL_PRESENT) {
			String traceId = Otel.traceId();
			if (traceId != null) {
				return traceId;
			}
		}
		String fromMdc = MDC.get(MDC_TRACE_ID);
		return isValidTraceId(fromMdc) ? fromMdc : null;
	}

	/** 当前 span id：OTel 优先，其次 MDC；都没有返回 {@code null}。 */
	public static String currentSpanId() {
		if (OTEL_PRESENT) {
			String spanId = Otel.spanId();
			if (spanId != null) {
				return spanId;
			}
		}
		String fromMdc = MDC.get(MDC_SPAN_ID);
		return isValidSpanId(fromMdc) ? fromMdc : null;
	}

	/**
	 * 出站调用要带的 {@code traceparent}：同一 trace、新的 span id（代表这一次出站调用）。
	 *
	 * <p>OTel span 有效时返回 {@code null} —— agent 会自己注入，这里再写一份就成了两个互相打架的头。
	 * 当前线程没有 trace（定时任务、没传 MDC 的异步线程）时也返回 {@code null}：让被调方自己起一个，
	 * 比编一个与谁都对不上的 id 强。
	 */
	public static String outboundTraceparent() {
		if (otelSpanValid()) {
			return null;
		}
		String traceId = MDC.get(MDC_TRACE_ID);
		return isValidTraceId(traceId) ? traceparent(traceId, newSpanId()) : null;
	}

	/**
	 * 回给终端的错误号：trace id 的末 8 位（用户念得出来、按后缀能反查到整条链路）；
	 * 没有 trace id 时退回随机 8 位 hex（与改造前同形）。
	 */
	public static String errorNo(String traceId) {
		if (isValidTraceId(traceId)) {
			return traceId.substring(24);
		}
		return hex16(ThreadLocalRandom.current().nextLong()).substring(8);
	}

	/** 给 OTel 当前 span 打属性；span 无效或出任何错都静默。 */
	public static void setSpanAttribute(String key, String value) {
		if (OTEL_PRESENT && key != null && value != null) {
			Otel.setAttribute(key, value);
		}
	}

	private static boolean isLowerHex(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
				return false;
			}
		}
		return !s.isEmpty();
	}

	private static String hex16(long value) {
		String hex = Long.toHexString(value);
		return hex.length() == 16 ? hex : "0".repeat(16 - hex.length()) + hex;
	}

	private static boolean isPresent(String className) {
		try {
			Class.forName(className, false, TraceIds.class.getClassLoader());
			return true;
		}
		catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	/** OTel API 的唯一触点。任何异常（含 agent 桥接异常）都当作「没有 span」。 */
	private static final class Otel {

		private Otel() {
		}

		static boolean valid() {
			try {
				return io.opentelemetry.api.trace.Span.current().getSpanContext().isValid();
			}
			catch (RuntimeException | LinkageError e) {
				return false;
			}
		}

		static String traceId() {
			try {
				io.opentelemetry.api.trace.SpanContext ctx = io.opentelemetry.api.trace.Span.current().getSpanContext();
				return ctx.isValid() ? ctx.getTraceId() : null;
			}
			catch (RuntimeException | LinkageError e) {
				return null;
			}
		}

		static String spanId() {
			try {
				io.opentelemetry.api.trace.SpanContext ctx = io.opentelemetry.api.trace.Span.current().getSpanContext();
				return ctx.isValid() ? ctx.getSpanId() : null;
			}
			catch (RuntimeException | LinkageError e) {
				return null;
			}
		}

		static void setAttribute(String key, String value) {
			try {
				io.opentelemetry.api.trace.Span span = io.opentelemetry.api.trace.Span.current();
				if (span.getSpanContext().isValid()) {
					span.setAttribute(key, value);
				}
			}
			catch (RuntimeException | LinkageError e) {
				// 观测代码不打断业务
			}
		}

	}

}
