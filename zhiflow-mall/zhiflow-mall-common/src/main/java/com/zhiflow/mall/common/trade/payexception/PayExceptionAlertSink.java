package com.zhiflow.mall.common.trade.payexception;

import com.zhiflow.mall.common.entity.PayException;

/**
 * 入池告警的出口（「入池即告警，告警带类型、金额、订单号、处置链接」）。
 *
 * <p>两条告警并行：Prometheus 计数器（{@code pay_exception_recorded_total}，按类型聚合进钉钉告警群）
 * 由 {@link PayExceptionRecorder} 自己记；带明细的站内信 + 钉钉旁路由服务侧实现本接口
 * （复用 {@code AdminNotificationService}）。实现必须吞掉自身异常——告警失败不能让入池回滚。
 */
public interface PayExceptionAlertSink {

	void onRecorded(PayException row);
}
