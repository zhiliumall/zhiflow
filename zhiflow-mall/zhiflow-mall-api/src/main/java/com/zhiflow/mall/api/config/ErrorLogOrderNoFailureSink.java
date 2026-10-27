package com.zhiflow.mall.api.config;

import com.zhiflow.common.log.error.ErrorLogRecorder;
import com.zhiflow.mall.common.orderno.OrderNoFailureSink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把订单号失败事件桥接到 {@code sys_error_log}（mall-api 侧实现）。
 *
 * <p>mall-common 拿不到 {@code ErrorLogRecorder}（它在 common-log，加依赖会把 common-security
 * 拖进 mall-common 的传递链），所以那边只定义 {@link OrderNoFailureSink} 接缝，
 * 真实落库在各服务侧实现。
 *
 * <p>C 端下单是订单号的主生成路径，这条 sink 尤其重要——失败只留容器日志的话，
 * 容器重启即丢，也没有「同一个错误出现了多少次」的聚合键。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorLogOrderNoFailureSink implements OrderNoFailureSink {

	private final ErrorLogRecorder errorLogRecorder;

	@Override
	public void record(String reason, String detail, Throwable cause) {
		// 最佳努力：本方法在失败路径上被调用，自身再抛异常会把原始故障盖掉
		try {
			Throwable throwable = cause != null ? cause
					: new IllegalStateException("orderNo failure: " + reason);
			errorLogRecorder.record(throwable, "orderNoFailure reason=" + reason + " detail=" + detail);
		}
		catch (Exception e) {
			log.warn("订单号失败事件落库失败 reason={} detail={}", reason, detail, e);
		}
	}
}
