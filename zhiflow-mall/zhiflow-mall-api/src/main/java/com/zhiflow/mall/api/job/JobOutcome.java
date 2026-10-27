package com.zhiflow.mall.api.job;

import io.micrometer.core.instrument.Metrics;

/**
 * 定时任务的结论（支付与订单状态机 v1.2 阶段 0 · 0.10）。
 *
 * <p>此前单笔 / 单租户失败都只计进消息串、本轮仍报成功，任务坏了没人知道（v1.0 8.5、评审 24）。
 *
 * <p>规矩：<b>任一单笔或单租户失败，本轮就报失败</b>（指标切面记 {@code job_runs_total{outcome=failure}}），
 * 同时计数 {@code job_item_failures_total{job}}。
 * 「跳过」（锁被占、无租户、无数据）不是失败。
 */
public final class JobOutcome {

	static final String METRIC = "job_item_failures_total";

	private JobOutcome() {
	}

	/**
	 * @param job            任务名（任务类的 bean 名，作指标标签）
	 * @param itemFailures   单笔失败数
	 * @param tenantFailures 单租户失败数
	 * @param summary        本轮统计摘要
	 */
	public static JobResult of(String job, int itemFailures, int tenantFailures, String summary) {
		int failures = itemFailures + tenantFailures;
		if (failures == 0) {
			return JobResult.ok(summary);
		}
		Metrics.counter(METRIC, "job", job).increment(failures);
		return JobResult.failed("本轮有失败（单笔 " + itemFailures + "，租户 " + tenantFailures + "）：" + summary);
	}
}
