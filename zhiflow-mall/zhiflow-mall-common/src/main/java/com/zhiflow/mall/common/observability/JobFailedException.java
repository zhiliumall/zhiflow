package com.zhiflow.mall.common.observability;

/**
 * 定时任务「跑完了但有失败」（如按租户循环时部分租户失败）。
 *
 * <p>逐租户任务按规矩吞掉单租户异常、继续跑下一个——于是整轮在调度器眼里永远是成功的。
 * 循环结束后抛这个异常，任务切面把它记成 {@code job_runs_total{outcome="failure"}}（与抛了未预期异常的 error 区分），
 * 失败即告警（「失败即 P1」）。
 */
public class JobFailedException extends RuntimeException {

	public JobFailedException(String message) {
		super(message);
	}
}
