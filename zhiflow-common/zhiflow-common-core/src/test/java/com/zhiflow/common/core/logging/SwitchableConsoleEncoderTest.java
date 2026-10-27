package com.zhiflow.common.core.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.event.KeyValuePair;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两种模式都要产出预期内容，且 ECS 起不来时要退回文本而不是没有编码器。
 */
class SwitchableConsoleEncoderTest {

	private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	@DisplayName("默认 text：与原 console pattern 同形，[%X{trace.id}] 槽位里是 MDC 的 trace id")
	void textModeByDefault() {
		LoggerContext context = newContext();
		SwitchableConsoleEncoder encoder = encoder(context, null);

		String line = encode(encoder, context, "hello");

		assertThat(encoder.getActiveFormat()).isEqualTo("text");
		assertThat(line).contains("[" + TRACE + "]").contains("INFO").contains(" -hello");
		assertThat(line.trim()).doesNotStartWith("{");
	}

	@Test
	@DisplayName("ecs：输出一行合法 JSON，MDC 的 trace.id / tenant.id 按 ECS 惯例嵌套，service.name 取应用名")
	void ecsMode() throws Exception {
		LoggerContext context = newContext();
		context.putObject(Environment.class.getName(),
				new MockEnvironment().withProperty("spring.application.name", "zhiflow-mall-api"));
		SwitchableConsoleEncoder encoder = encoder(context, "ecs");

		String line = encode(encoder, context, "hello");

		assertThat(encoder.getActiveFormat()).isEqualTo("ecs");
		JsonNode json = new JsonMapper().readTree(line);
		assertThat(json.get("message").asString()).isEqualTo("hello");
		assertThat(json.get("log").get("level").asString()).isEqualTo("INFO");
		assertThat(json.get("trace").get("id").asString()).isEqualTo(TRACE);
		assertThat(json.get("tenant").get("id").asString()).isEqualTo("1");
		assertThat(json.get("service").get("name").asString()).isEqualTo("zhiflow-mall-api");
	}

	@Test
	@DisplayName("ecs：SLF4J key-value（网关访问日志用的）也进 JSON，带点的键同样嵌套")
	void ecsIncludesKeyValuePairs() throws Exception {
		LoggerContext context = newContext();
		context.putObject(Environment.class.getName(), new MockEnvironment());
		SwitchableConsoleEncoder encoder = encoder(context, "ecs");
		LoggingEvent event = new LoggingEvent(SwitchableConsoleEncoderTest.class.getName(), context.getLogger("access"),
				Level.INFO, "access GET /x 200 3ms", null, null);
		event.addKeyValuePair(new KeyValuePair("route", "mall-api"));
		event.addKeyValuePair(new KeyValuePair("client.ip", "203.0.113.0"));
		event.addKeyValuePair(new KeyValuePair("duration_ms", 3L));

		JsonNode json = new JsonMapper().readTree(new String(encoder.encode(event), StandardCharsets.UTF_8));

		assertThat(json.get("route").asString()).isEqualTo("mall-api");
		assertThat(json.get("client").get("ip").asString()).isEqualTo("203.0.113.0");
		assertThat(json.get("duration_ms").asLong()).isEqualTo(3L);
	}

	@Test
	@DisplayName("ecs：带异常的行 + MDC error.code（全局异常处理器的 500 现场）不丢行：error 对象是异常，错误码改扁平 error_code")
	void ecsErrorCodeWithThrowableIsNotDropped() throws Exception {
		LoggerContext context = newContext();
		context.putObject(Environment.class.getName(), new MockEnvironment());
		SwitchableConsoleEncoder encoder = encoder(context, "ecs");
		MDC.put("trace.id", TRACE);
		MDC.put("error.code", "SYS-COMMON-500");
		LoggingEvent event = new LoggingEvent(SwitchableConsoleEncoderTest.class.getName(), context.getLogger("test"),
				Level.ERROR, "全局异常信息", new IllegalStateException("boom"), null);

		JsonNode json = new JsonMapper().readTree(new String(encoder.encode(event), StandardCharsets.UTF_8));

		assertThat(json.get("error").get("type").asString()).isEqualTo(IllegalStateException.class.getName());
		assertThat(json.get("error").get("stack_trace").asString()).contains("boom");
		assertThat(json.get("error_code").asString()).isEqualTo("SYS-COMMON-500");
		assertThat(json.get("trace").get("id").asString()).isEqualTo(TRACE);
	}

	@Test
	@DisplayName("ecs：不带异常的行，error.code 照常嵌套在 error 下（对照：改名只发生在会撞名的那种行上）")
	void ecsErrorCodeWithoutThrowableStaysNested() throws Exception {
		LoggerContext context = newContext();
		context.putObject(Environment.class.getName(), new MockEnvironment());
		SwitchableConsoleEncoder encoder = encoder(context, "ecs");
		MDC.put("error.code", "BIZ-COMMON-001");
		LoggingEvent event = new LoggingEvent(SwitchableConsoleEncoderTest.class.getName(), context.getLogger("test"),
				Level.WARN, "业务拒绝", null, null);

		JsonNode json = new JsonMapper().readTree(new String(encoder.encode(event), StandardCharsets.UTF_8));

		assertThat(json.get("error").get("code").asString()).isEqualTo("BIZ-COMMON-001");
		assertThat(json.get("error_code")).isNull();
	}

	@Test
	@DisplayName("ecs：其它任何撞名（如 MDC 里有 message）导致 JSON 写不出来时，这一行退回文本输出而不是消失")
	void ecsEncodingFailureFallsBackToText() {
		LoggerContext context = newContext();
		context.putObject(Environment.class.getName(), new MockEnvironment());
		SwitchableConsoleEncoder encoder = encoder(context, "ecs");
		MDC.put("message", "与 ECS 自带字段同名");

		String line = encode(encoder, context, "hello");

		assertThat(encoder.getActiveFormat()).isEqualTo("ecs");
		assertThat(line).contains("[" + TRACE + "]").contains(" -hello");
		assertThat(line.trim()).doesNotStartWith("{");
	}

	@Test
	@DisplayName("对照：logback 上下文里没有 Spring Environment 时 ECS 起不来 → 退回文本，仍能输出")
	void ecsFallsBackToTextWithoutEnvironment() {
		LoggerContext context = newContext();
		SwitchableConsoleEncoder encoder = encoder(context, "ecs");

		String line = encode(encoder, context, "hello");

		assertThat(encoder.getActiveFormat()).isEqualTo("text");
		assertThat(line).contains("[" + TRACE + "]");
	}

	@Test
	@DisplayName("认不出的取值按 text")
	void unknownFormatIsText() {
		LoggerContext context = newContext();
		assertThat(encoder(context, "logstash-v9").getActiveFormat()).isEqualTo("text");
	}

	/** 新建上下文要接上 SLF4J 的 MDC 适配器，否则事件读不到 {@code MDC.put} 的值。 */
	private static LoggerContext newContext() {
		LoggerContext context = new LoggerContext();
		context.setMDCAdapter(MDC.getMDCAdapter());
		return context;
	}

	private static SwitchableConsoleEncoder encoder(LoggerContext context, String format) {
		SwitchableConsoleEncoder encoder = new SwitchableConsoleEncoder();
		encoder.setContext(context);
		encoder.setPattern(SwitchableConsoleEncoder.DEFAULT_PATTERN);
		if (format != null) {
			encoder.setFormat(format);
		}
		encoder.start();
		return encoder;
	}

	private static String encode(SwitchableConsoleEncoder encoder, LoggerContext context, String message) {
		MDC.put("trace.id", TRACE);
		MDC.put("tenant.id", "1");
		LoggingEvent event = new LoggingEvent(SwitchableConsoleEncoderTest.class.getName(),
				context.getLogger("test"), Level.INFO, message, null, null);
		return new String(encoder.encode(event), StandardCharsets.UTF_8);
	}

}
