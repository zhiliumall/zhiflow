package com.zhiflow.common.log.event;

import com.zhiflow.api.upms.LogApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.log.config.BaseLogProperties;
import com.zhiflow.common.log.fallback.AuditFallbackWriter;
import com.zhiflow.upms.common.dto.SysLogPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author 辰
 * @date 2023年10月27日11:29:24
 * 异步监听日志事件
 */
@Slf4j
@RequiredArgsConstructor
public class SysLogListener {

	/**
	 * 承载阻塞 saveLog 的虚拟线程 executor：短超时（5s）后释放 syslog- 线程，
	 * 底层 socket read 仍会挂到 HTTP 读超时(120s)，但虚拟线程 park 成本极低。
	 */
	private final static Executor SAVE_LOG_HTTP_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

	/** 审计日志 saveLog 短超时（秒）：不复用 http.client 的 120s 读超时 */
	private final static long SAVE_LOG_TIMEOUT_SECONDS = 5;

	private final LogApi logHttpClient;

	private final BaseLogProperties baseLogProperties;

	private final AuditFallbackWriter fallbackWriter;

	@Async("sysLogExecutor")
	@Order
	@EventListener(SysLogEvent.class)
	public void saveSysLog(SysLogEvent event) {
		SysLogPayload sysLog = (SysLogPayload) event.getSource();
		if (!baseLogProperties.isEnabled()) {
			return;
		}
		try {
			// 短超时兜底：upms 慢/挂时 5s 释放线程
			CompletableFuture
					.runAsync(() -> logHttpClient.saveLog(sysLog, SecurityConstants.FROM_IN), SAVE_LOG_HTTP_EXECUTOR)
					.orTimeout(SAVE_LOG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
					.join();
		}
		catch (Exception e) {
			// 改造前这里只有一句 log.warn 且注释写着「审计日志可丢」——丢了之后无处追查。
			// 现在转本地兜底落盘，内容仍可恢复。仍然不做同步重试：绝不把 upms 的故障
			// 通过阻塞回灌到业务侧。
			fallbackWriter.write(sysLog, "上报 upms 失败或超时: " + e.getMessage());
		}
	}

}
