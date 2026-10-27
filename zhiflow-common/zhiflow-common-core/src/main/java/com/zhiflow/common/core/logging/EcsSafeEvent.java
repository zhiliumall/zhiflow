package com.zhiflow.common.core.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交给 ECS 编码器之前，把会与异常字段撞名的 MDC 键改名。
 *
 * <p>Spring Boot 的 ECS 格式把异常写成 {@code "error":{"type","message","stack_trace"}}，又把带点的 MDC 键嵌套输出：
 * MDC 里有 {@code error.code}（全局异常处理器打上的错误码）就会再写一个 {@code "error":{"code"}}。
 * Boot 的 JSON 写出器不允许同一对象里重名，直接抛 {@code IllegalStateException: The name 'error' has already been written}，
 * logback 吞掉异常 —— <b>这一行日志整行丢失</b>，而它恰恰是带堆栈的那一行（500 的现场）。
 *
 * <p>所以带异常的事件里，{@code error.xxx} 改成扁平的 {@code error_xxx}（值不变，按错误码搜日志仍能搜到）；
 * 不带异常的事件原样放行，{@code error.code} 照常嵌套输出。
 */
final class EcsSafeEvent implements ILoggingEvent {

	private static final String ERROR_PREFIX = "error.";

	private final ILoggingEvent delegate;

	private final Map<String, String> mdc;

	private EcsSafeEvent(ILoggingEvent delegate, Map<String, String> mdc) {
		this.delegate = delegate;
		this.mdc = mdc;
	}

	/** 没有撞名风险时原样返回同一个事件（绝大多数日志行走这条，零分配）。 */
	static ILoggingEvent of(ILoggingEvent event) {
		if (event.getThrowableProxy() == null) {
			return event;
		}
		Map<String, String> original = event.getMDCPropertyMap();
		if (original == null || original.keySet().stream().noneMatch(key -> key.startsWith(ERROR_PREFIX))) {
			return event;
		}
		Map<String, String> renamed = new LinkedHashMap<>(original.size());
		original.forEach((key, value) -> renamed
			.put(key.startsWith(ERROR_PREFIX) ? "error_" + key.substring(ERROR_PREFIX.length()) : key, value));
		return new EcsSafeEvent(event, renamed);
	}

	@Override
	public Map<String, String> getMDCPropertyMap() {
		return mdc;
	}

	@Override
	@SuppressWarnings("deprecation")
	public Map<String, String> getMdc() {
		return mdc;
	}

	@Override
	public String getThreadName() {
		return delegate.getThreadName();
	}

	@Override
	public Level getLevel() {
		return delegate.getLevel();
	}

	@Override
	public String getMessage() {
		return delegate.getMessage();
	}

	@Override
	public Object[] getArgumentArray() {
		return delegate.getArgumentArray();
	}

	@Override
	public String getFormattedMessage() {
		return delegate.getFormattedMessage();
	}

	@Override
	public String getLoggerName() {
		return delegate.getLoggerName();
	}

	@Override
	public LoggerContextVO getLoggerContextVO() {
		return delegate.getLoggerContextVO();
	}

	@Override
	public IThrowableProxy getThrowableProxy() {
		return delegate.getThrowableProxy();
	}

	@Override
	public StackTraceElement[] getCallerData() {
		return delegate.getCallerData();
	}

	@Override
	public boolean hasCallerData() {
		return delegate.hasCallerData();
	}

	@Override
	@SuppressWarnings("deprecation")
	public Marker getMarker() {
		return delegate.getMarker();
	}

	@Override
	public List<Marker> getMarkerList() {
		return delegate.getMarkerList();
	}

	@Override
	public long getTimeStamp() {
		return delegate.getTimeStamp();
	}

	@Override
	public int getNanoseconds() {
		return delegate.getNanoseconds();
	}

	@Override
	public Instant getInstant() {
		return delegate.getInstant();
	}

	@Override
	public long getSequenceNumber() {
		return delegate.getSequenceNumber();
	}

	@Override
	public List<KeyValuePair> getKeyValuePairs() {
		return delegate.getKeyValuePairs();
	}

	@Override
	public void prepareForDeferredProcessing() {
		delegate.prepareForDeferredProcessing();
	}

}
