package com.zhiflow.common.core.observability;

import org.slf4j.MDC;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 三段式错误码 {@code <层>-<域>-<码>}，经响应头 {@value #HEADER} 交给网关算 outcome。
 *
 * <h2>为什么走响应头而不是 R 里加字段</h2>
 * <p>{@code R} 会进 Redis 缓存，缓存的 ObjectMapper 不忽略未知字段 —— 给 R 加字段等于让所有值为 R 的缓存
 * 命中率归零（{@code isOk()} 被序列化成 {@code ok} 字段时就出过一次）。响应头对响应体零影响。
 *
 * <h2>层的语义（网关 outcome 只看层）</h2>
 * <ul>
 *   <li>{@code SYS-*}：服务端故障，计入错误率；</li>
 *   <li>{@code EXT-*}：外部依赖故障，计入错误率；</li>
 *   <li>{@code BIZ-*}：业务预期内的拒绝（余额不足、参数错、没权限），<b>不</b>计入错误率。</li>
 * </ul>
 * 各业务异常逐个编码随接口加固方案走；这里只定义框架层能判定的那几个。
 */
public final class ErrorCode {

	public static final String HEADER = "X-Error-Code";

	public static final String MDC_KEY = "error.code";

	/** 未捕获异常 */
	public static final String SYS_COMMON = "SYS-COMMON-500";

	/** SQL / 数据访问 */
	public static final String SYS_DB = "SYS-DB-500";

	public static final String SYS_TIMEOUT = "SYS-TIMEOUT-504";

	/** 网关连不上上游（拒绝连接 / 连接超时） */
	public static final String SYS_UPSTREAM_UNREACHABLE = "SYS-UPSTREAM-502";

	/** 网关找不到可用实例（注册中心里没有该服务） */
	public static final String SYS_UPSTREAM_UNAVAILABLE = "SYS-UPSTREAM-503";

	/** 通用业务拒绝（BizException） */
	public static final String BIZ_COMMON = "BIZ-COMMON-001";

	/** 参数 / 校验 */
	public static final String BIZ_PARAM = "BIZ-PARAM-400";

	/** 未登录 */
	public static final String BIZ_AUTH_UNAUTHORIZED = "BIZ-AUTH-401";

	/** 无权限 */
	public static final String BIZ_AUTH_FORBIDDEN = "BIZ-AUTH-403";

	/** 令牌失效（沿用既有的 424 语义） */
	public static final String BIZ_AUTH_TOKEN_INVALID = "BIZ-AUTH-424";

	/** 分佣结算 / 提现审核暂停中（止血开关） */
	public static final String BIZ_AFFILIATE_PAUSED = "BIZ-AFFIL-090";

	public static final String BIZ_NOT_FOUND = "BIZ-NOTFOUND-404";

	public static final String BIZ_METHOD = "BIZ-METHOD-405";

	public static final String OUTCOME_SUCCESS = "success";

	public static final String OUTCOME_BIZ_REJECT = "biz_reject";

	public static final String OUTCOME_ERROR = "error";

	private static final Pattern WELL_FORMED = Pattern.compile("^(SYS|EXT|BIZ)-[A-Z0-9]+-\\d{3}$");

	private ErrorCode() {
	}

	/**
	 * 异常自带的错误码（实现了 {@link ErrorCoded} 且格式合规）优先，否则用 {@code fallback}。
	 * 例：结算暂停异常继承 BizException、自带 {@code BIZ-AFFIL-090}，走这里而不是落成 {@code BIZ-COMMON-001}。
	 */
	public static String codeOf(Throwable throwable, String fallback) {
		if (throwable instanceof ErrorCoded coded) {
			try {
				String code = coded.errorCode();
				if (code != null && WELL_FORMED.matcher(code).matches()) {
					return code;
				}
			}
			catch (RuntimeException e) {
				// 取码失败就用默认码，观测代码不打断异常处理
			}
		}
		return fallback;
	}

	/** 外部依赖故障码，如 {@code ext("sms") → EXT-SMS-502}。 */
	public static String ext(String dependency) {
		return "EXT-" + dependency.toUpperCase(Locale.ROOT) + "-502";
	}

	/**
	 * 业务结果：错误码优先（{@code SYS-/EXT-} → error，{@code BIZ-} → biz_reject），
	 * 没有错误码时按状态码（5xx → error，4xx → biz_reject，其余 success）。
	 *
	 * <p>错误码优先的一个后果是有意为之的：回调路径上的 {@code BizException} 按 R5 回调闸保持 HTTP 500
	 * （为了维持渠道重试窗），它带的是 {@code BIZ-COMMON-001}，这里记 biz_reject —— 它不是服务故障，
	 * 回调是否入账另由支付回调的结果指标看。
	 */
	public static String outcome(String errorCode, int status) {
		if (errorCode != null && !errorCode.isBlank()) {
			if (errorCode.startsWith("SYS-") || errorCode.startsWith("EXT-")) {
				return OUTCOME_ERROR;
			}
			if (errorCode.startsWith("BIZ-")) {
				return OUTCOME_BIZ_REJECT;
			}
		}
		if (status >= 500) {
			return OUTCOME_ERROR;
		}
		if (status >= 400) {
			return OUTCOME_BIZ_REJECT;
		}
		return OUTCOME_SUCCESS;
	}

	/**
	 * 未捕获异常 → 系统层错误码：沿 cause 链找数据访问 / 超时的迹象，找不到就是 {@link #SYS_COMMON}。
	 * 按类名判定，不给 common-core 引 spring-tx / mybatis 依赖。
	 */
	public static String systemCodeOf(Throwable throwable) {
		Throwable t = throwable;
		for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
			String name = t.getClass().getName();
			if (t instanceof java.sql.SQLException || name.startsWith("org.springframework.dao.")
					|| name.startsWith("org.apache.ibatis.") || name.startsWith("org.mybatis.")
					|| name.startsWith("org.springframework.jdbc.")) {
				return SYS_DB;
			}
			if (t instanceof java.util.concurrent.TimeoutException || t instanceof java.net.SocketTimeoutException
					|| name.equals("java.net.http.HttpTimeoutException")) {
				return SYS_TIMEOUT;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return SYS_COMMON;
	}

	/**
	 * 给当前请求打上错误码：写 MDC（让这次请求随后的日志行带上它）与当前 servlet 响应头。
	 *
	 * <p>拿不到 servlet 响应（WebFlux、非请求线程、测试里直接 new 处理器）时只写 MDC。
	 * MDC 由请求入口的过滤器在 finally 里清掉。本方法绝不抛异常。
	 */
	public static void markCurrentRequest(String code) {
		if (code == null) {
			return;
		}
		try {
			MDC.put(MDC_KEY, code);
			RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
			if (attributes instanceof ServletRequestAttributes servlet && servlet.getResponse() != null
					&& !servlet.getResponse().isCommitted()) {
				servlet.getResponse().setHeader(HEADER, code);
			}
		}
		catch (RuntimeException | LinkageError e) {
			// 观测代码不打断业务
		}
	}

}
