package com.zhiflow.common.log.trace;

import com.zhiflow.common.core.observability.ErrorCode;
import com.zhiflow.common.core.observability.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求入口的链路标识：把 trace id 放进 MDC、写响应头 {@value TraceIds#TRACE_ID_HEADER}，出口清干净。
 *
 * <p>trace id 取值顺序：
 * <ol>
 *   <li>OTel 当前 span（挂了 agent 时 agent 已按入站 {@code traceparent} 建好 server span）——以它为准，
 *       日志与 Tempo 才对得上；</li>
 *   <li>入站 {@code traceparent}（网关生成/延续的那个）——没挂 agent 时整条链路靠它串起来；</li>
 *   <li>都没有（直连端口、内网探活）——现生成一个。</li>
 * </ol>
 *
 * <p>出口在 finally 里把本类与下游写进 MDC 的观测键（trace / span / tenant / user / error.code）全部移除：
 * Tomcat 线程是复用的，漏清一个就会让下一个请求的日志顶着上一个请求的身份。
 *
 * <p>排在过滤器链最前面（{@code HIGHEST_PRECEDENCE + 5}），这样安全链、租户过滤器乃至被它们拒掉的请求打的日志都带 trace id。
 */
public class TraceMdcFilter extends OncePerRequestFilter {

	/** 请求属性：本次请求解析出的 trace id，给不方便读 MDC 的地方用。 */
	public static final String TRACE_ID_ATTRIBUTE = TraceMdcFilter.class.getName() + ".traceId";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String traceId = resolveTraceId(request);
		String spanId = TraceIds.otelSpanValid() ? TraceIds.currentSpanId() : null;
		if (spanId == null) {
			spanId = TraceIds.newSpanId();
		}
		try {
			MDC.put(TraceIds.MDC_TRACE_ID, traceId);
			MDC.put(TraceIds.MDC_SPAN_ID, spanId);
			request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
			response.setHeader(TraceIds.TRACE_ID_HEADER, traceId);
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(TraceIds.MDC_TRACE_ID);
			MDC.remove(TraceIds.MDC_SPAN_ID);
			MDC.remove(TraceIds.MDC_TENANT_ID);
			MDC.remove(TraceIds.MDC_USER_ID);
			MDC.remove(ErrorCode.MDC_KEY);
		}
	}

	static String resolveTraceId(HttpServletRequest request) {
		if (TraceIds.otelSpanValid()) {
			return TraceIds.currentTraceId();
		}
		// 异步 / 错误派发再次进来时沿用首次派发定下的 id（首次是现生成的话，入站头里并没有它）
		if (request.getAttribute(TRACE_ID_ATTRIBUTE) instanceof String previous && TraceIds.isValidTraceId(previous)) {
			return previous;
		}
		TraceIds.TraceParent incoming = TraceIds.parse(request.getHeader(TraceIds.TRACEPARENT));
		return incoming != null ? incoming.traceId() : TraceIds.newTraceId();
	}

	/**
	 * 异步派发（SSE / DeferredResult）回到容器线程时也要带上 trace id，否则那段日志没有 id。
	 * 错误派发同理：/error 里打的日志要能对回原请求。
	 */
	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return false;
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

}
