package com.zhiflow.mall.common.orderno;

/**
 * {@link OrderNoFailureSink} 的缺省实现：什么都不做。
 *
 * <p>失败信息此时已经进了 ERROR 日志与 Micrometer 指标，本 Sink 只是额外的落库出口。
 * 真实实现由 mall-api / mall-admin 提供（桥接 {@code ErrorLogRecorder}）。
 */
public class NoOpOrderNoFailureSink implements OrderNoFailureSink {

	@Override
	public void record(String reason, String detail, Throwable cause) {
		// no-op，见类注释
	}
}
