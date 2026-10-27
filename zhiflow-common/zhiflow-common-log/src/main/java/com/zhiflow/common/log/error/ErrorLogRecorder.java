package com.zhiflow.common.log.error;

import com.zhiflow.common.log.config.BaseLogProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * 显式记录错误日志，供业务 {@code catch} 块调用。
 *
 * <p>全局异常处理器只能看到抛到它面前的异常；业务里「catch 后 return R.failed」的写法会把异常吞掉。
 * 在这类 catch 块里补一行 {@code errorLogRecorder.record(e)}，异常就会以 ERROR 级别带完整堆栈进日志。
 *
 * <p>开源版不保留报错日志表与后台页面，这里只写本地日志。
 */
@Slf4j
public class ErrorLogRecorder {

	private final BaseLogProperties properties;

	public ErrorLogRecorder(BaseLogProperties properties) {
		this.properties = properties;
	}

	public void record(Throwable throwable) {
		record(throwable, null);
	}

	public void record(Throwable throwable, String requestParams) {
		if (!properties.isEnabled() || throwable == null) {
			return;
		}
		log.error("[error-log] requestParams={}", requestParams, throwable);
	}
}
