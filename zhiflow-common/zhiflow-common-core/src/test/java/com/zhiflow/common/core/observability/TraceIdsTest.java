package com.zhiflow.common.core.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3C traceparent 的解析/生成与「当前 trace id」的取值顺序。
 * 没挂 OTel agent 的单测进程里 {@code Span.current()} 恒无效，走的正是默认部署形态的那条分支。
 */
class TraceIdsTest {

	private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

	private static final String SPAN = "00f067aa0ba902b7";

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	@DisplayName("对照：规范里的示例 traceparent 能解析出 trace id / parent id / flags")
	void parsesSpecExample() {
		TraceIds.TraceParent parsed = TraceIds.parse("00-" + TRACE + "-" + SPAN + "-01");
		assertThat(parsed).isNotNull();
		assertThat(parsed.traceId()).isEqualTo(TRACE);
		assertThat(parsed.parentSpanId()).isEqualTo(SPAN);
		assertThat(parsed.flags()).isEqualTo("01");
		assertThat(parsed.header()).isEqualTo("00-" + TRACE + "-" + SPAN + "-01");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"",
			"garbage",
			"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7",           // 缺 flags
			"00-00000000000000000000000000000000-00f067aa0ba902b7-01",        // trace id 全 0
			"00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",        // parent id 全 0
			"ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",        // version ff 非法
			"00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",        // 必须小写
			"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra",  // 00 版必须恰好 55 字符
			"00_4bf92f3577b34da6a3ce929d0e0e4736_00f067aa0ba902b7_01",        // 分隔符错
			"00-4bf92f3577b34da6a3ce929d0e0e473g-00f067aa0ba902b7-01"         // 非 hex
	})
	@DisplayName("不合法的 traceparent 一律返回 null（调用方据此重新生成，不沿用坏值）")
	void rejectsInvalid(String header) {
		assertThat(TraceIds.parse(header)).isNull();
	}

	@Test
	@DisplayName("更高版本允许在第 55 位之后以 '-' 追加字段；null 返回 null")
	void futureVersionAndNull() {
		assertThat(TraceIds.parse("01-" + TRACE + "-" + SPAN + "-01-what-ever")).isNotNull();
		assertThat(TraceIds.parse(null)).isNull();
	}

	@Test
	@DisplayName("生成的 id 形状合法、生成的 traceparent 能被自己解析回来")
	void generatedIdsRoundTrip() {
		for (int i = 0; i < 200; i++) {
			String traceId = TraceIds.newTraceId();
			String spanId = TraceIds.newSpanId();
			assertThat(TraceIds.isValidTraceId(traceId)).isTrue();
			assertThat(TraceIds.isValidSpanId(spanId)).isTrue();
			TraceIds.TraceParent parsed = TraceIds.parse(TraceIds.traceparent(traceId, spanId));
			assertThat(parsed).isNotNull();
			assertThat(parsed.traceId()).isEqualTo(traceId);
			assertThat(parsed.parentSpanId()).isEqualTo(spanId);
		}
	}

	@Test
	@DisplayName("没有 OTel span 时 currentTraceId 取 MDC；MDC 里是坏值 / 没有 → null")
	void currentTraceIdFallsBackToMdc() {
		assertThat(TraceIds.otelSpanValid()).isFalse();
		assertThat(TraceIds.currentTraceId()).isNull();

		MDC.put(TraceIds.MDC_TRACE_ID, TRACE);
		assertThat(TraceIds.currentTraceId()).isEqualTo(TRACE);

		MDC.put(TraceIds.MDC_TRACE_ID, "not-a-trace-id");
		assertThat(TraceIds.currentTraceId()).isNull();
	}

	@Test
	@DisplayName("出站 traceparent：同一 trace、新 span；当前没有 trace 时不编一个")
	void outboundTraceparent() {
		assertThat(TraceIds.outboundTraceparent()).isNull();

		MDC.put(TraceIds.MDC_TRACE_ID, TRACE);
		MDC.put(TraceIds.MDC_SPAN_ID, SPAN);
		TraceIds.TraceParent out = TraceIds.parse(TraceIds.outboundTraceparent());
		assertThat(out).isNotNull();
		assertThat(out.traceId()).isEqualTo(TRACE);
		assertThat(out.parentSpanId()).isNotEqualTo(SPAN);
	}

	@Test
	@DisplayName("错误号 = trace id 末 8 位；没有 trace id 时退回随机 8 位 hex")
	void errorNo() {
		assertThat(TraceIds.errorNo(TRACE)).isEqualTo("0e0e4736");
		assertThat(TRACE).endsWith(TraceIds.errorNo(TRACE));
		assertThat(TraceIds.errorNo(null)).matches("[0-9a-f]{8}");
		assertThat(TraceIds.errorNo("bad")).matches("[0-9a-f]{8}");
	}

	@Test
	@DisplayName("没有 span 时打 span 属性是空操作，不抛")
	void setSpanAttributeWithoutSpanIsNoop() {
		TraceIds.setSpanAttribute("tenant.id", "1");
		TraceIds.setSpanAttribute(null, null);
	}

}
