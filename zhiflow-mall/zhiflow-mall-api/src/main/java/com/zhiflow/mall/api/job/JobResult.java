package com.zhiflow.mall.api.job;

/**
 * 一轮定时任务的结论：是否成功 + 本轮摘要。由任务指标切面读取（失败记 {@code job_runs_total{outcome=failure}}），
 * 不交给任何调度框架。
 */
public record JobResult(boolean success, String message) {

	public static JobResult ok(String message) {
		return new JobResult(true, message);
	}

	public static JobResult failed(String message) {
		return new JobResult(false, message);
	}
}
