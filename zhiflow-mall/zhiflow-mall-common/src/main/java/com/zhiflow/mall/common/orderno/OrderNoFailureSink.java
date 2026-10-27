package com.zhiflow.mall.common.orderno;

/**
 * 订单号失败事件的落库出口。
 *
 * <p><b>为什么是接口而不是直接用 ErrorLogRecorder</b>：{@code ErrorLogRecorder} 在
 * {@code zhiflow-common-log}，而本模块（mall-common）只依赖 common-core / weixin-common /
 * upms-common / pay-common。把 common-log 加进来会将 common-security 一并拖进 mall-common
 * 的传递链——那正是 {@code GlobalBizExceptionHandler} 当年绕开的成环方向。
 *
 * <p>故此处只定义接缝，由 mall-api / mall-admin 各自提供桥接 {@code ErrorLogRecorder} 的实现。
 * 缺省实现 {@link NoOpOrderNoFailureSink} 什么都不做，保证库模块单独使用时不炸。
 *
 * <p>要求：实现必须是**最佳努力**的——本接口在失败路径上被调用，自身再抛异常会把
 * 原始故障盖掉。
 */
@FunctionalInterface
public interface OrderNoFailureSink {

	/**
	 * @param reason 失败原因，取值见 {@link OrderNoMetrics#REASON_CLOCK_ROLLBACK} /
	 *               {@link OrderNoMetrics#REASON_DUPLICATE}
	 * @param detail 排障用的上下文（回拨幅度 / 冲突的订单号等）
	 * @param cause  原始异常，可为 null
	 */
	void record(String reason, String detail, Throwable cause);
}
