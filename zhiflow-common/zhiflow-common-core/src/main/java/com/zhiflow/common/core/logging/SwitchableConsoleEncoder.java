package com.zhiflow.common.core.logging;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.EncoderBase;
import org.springframework.boot.logging.logback.StructuredLogEncoder;

import java.nio.charset.Charset;
import java.util.Locale;

/**
 * 控制台编码器：文本（默认，与改造前逐字节一致）或 ECS JSON，由 {@value #FORMAT_KEY} 切换。
 *
 * <p>用法（logback-spring.xml 的 console appender）：
 * <pre>{@code
 * <encoder class="com.zhiflow.common.core.logging.SwitchableConsoleEncoder">
 *     <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%X{trace.id}] [%thread] %-5level %logger{36} -%msg%n</pattern>
 * </encoder>
 * }</pre>
 *
 * <h2>取值顺序</h2>
 * {@code <format>} 元素 → 系统属性 {@value #FORMAT_KEY} → 环境变量 {@value #FORMAT_KEY} → {@code text}。
 * 只认 {@code text} / {@code ecs}，其余当 {@code text}：配错了退回已验证的格式，而不是打进没验证过的。
 *
 * <h2>为什么只切控制台</h2>
 * 控制台（容器 stdout）由 Alloy 采进 Loki，要机器可读；文件日志是人 grep 的，保持文本不打断现有排查习惯。
 *
 * <p>ECS 委托给 Spring Boot 自带的 {@link StructuredLogEncoder}：MDC 与 SLF4J key-value 都会写进 JSON，
 * 带点的键按 ECS 惯例嵌套（{@code trace.id → {"trace":{"id":…}}}）。它启动时要从 logback 上下文里取
 * Spring {@code Environment}（Boot 初始化日志系统时放进去）；取不到就启动失败 —— 这时本类退回文本并打一条 WARN
 * 状态，而不是让 console appender 整个没有编码器。
 *
 * <h2>ECS 模式绝不丢行</h2>
 * Boot 的 JSON 写出器遇到重名字段会直接抛异常，logback 吞掉异常、整行丢失。已知的撞名（MDC {@code error.code}
 * 与异常的 {@code error} 对象）由 {@link EcsSafeEvent} 事先避开；其余任何编码失败，这一行退回文本格式输出
 * （Alloy 两种格式都认），而不是消失。
 */
public class SwitchableConsoleEncoder extends EncoderBase<ILoggingEvent> {

	public static final String FORMAT_KEY = "LOG_CONSOLE_FORMAT";

	static final String FORMAT_TEXT = "text";

	static final String FORMAT_ECS = "ecs";

	/** 没配 {@code <pattern>} 时的文本格式：与各服务 console 原 pattern 同形。 */
	static final String DEFAULT_PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%X{trace.id}] [%thread] %-5level %logger{36} -%msg%n";

	private String pattern;

	private String format;

	private Charset charset;

	private Encoder<ILoggingEvent> delegate;

	/** 仅 ECS 模式：结构化编码失败时这一行改用文本输出。 */
	private Encoder<ILoggingEvent> textFallback;

	private String activeFormat;

	public void setPattern(String pattern) {
		this.pattern = pattern;
	}

	public void setFormat(String format) {
		this.format = format;
	}

	/** 不设时文本沿用 logback 默认（平台默认字符集，与改造前一致），ECS 用 UTF-8。 */
	public void setCharset(Charset charset) {
		this.charset = charset;
	}

	/** 实际生效的格式（text / ecs），供测试与排障。 */
	public String getActiveFormat() {
		return activeFormat;
	}

	@Override
	public void start() {
		String requested = resolveFormat();
		Encoder<ILoggingEvent> chosen = null;
		if (FORMAT_ECS.equals(requested)) {
			chosen = startEcs();
		}
		if (chosen == null) {
			chosen = startText();
			activeFormat = FORMAT_TEXT;
		}
		else {
			activeFormat = FORMAT_ECS;
			textFallback = startText();
		}
		delegate = chosen;
		super.start();
	}

	private String resolveFormat() {
		String value = firstNonBlank(format, System.getProperty(FORMAT_KEY), System.getenv(FORMAT_KEY));
		String normalized = value == null ? FORMAT_TEXT : value.trim().toLowerCase(Locale.ROOT);
		if (!FORMAT_TEXT.equals(normalized) && !FORMAT_ECS.equals(normalized)) {
			addWarn("未知的 " + FORMAT_KEY + "=" + value + "，按 text 输出");
			return FORMAT_TEXT;
		}
		return normalized;
	}

	private Encoder<ILoggingEvent> startEcs() {
		try {
			StructuredLogEncoder ecs = new StructuredLogEncoder();
			ecs.setContext(getContext());
			ecs.setFormat(FORMAT_ECS);
			if (charset != null) {
				ecs.setCharset(charset);
			}
			ecs.start();
			if (ecs.isStarted()) {
				return ecs;
			}
			addWarn("ECS 编码器未能启动，控制台退回文本格式");
		}
		catch (RuntimeException | LinkageError e) {
			addWarn("ECS 编码器启动失败，控制台退回文本格式", e);
		}
		return null;
	}

	private Encoder<ILoggingEvent> startText() {
		PatternLayoutEncoder text = new PatternLayoutEncoder();
		text.setContext(getContext());
		text.setPattern(pattern == null || pattern.isBlank() ? DEFAULT_PATTERN : pattern);
		if (charset != null) {
			text.setCharset(charset);
		}
		text.start();
		return text;
	}

	@Override
	public byte[] headerBytes() {
		return delegate == null ? null : delegate.headerBytes();
	}

	@Override
	public byte[] encode(ILoggingEvent event) {
		if (textFallback == null) {
			return delegate.encode(event);
		}
		try {
			return delegate.encode(EcsSafeEvent.of(event));
		}
		catch (RuntimeException e) {
			return textFallback.encode(event);
		}
	}

	@Override
	public byte[] footerBytes() {
		return delegate == null ? null : delegate.footerBytes();
	}

	@Override
	public void stop() {
		if (delegate != null) {
			delegate.stop();
		}
		if (textFallback != null) {
			textFallback.stop();
		}
		super.stop();
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

}
