package com.zhiflow.mall.admin.support;

import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.common.observability.JobFailedException;
import com.zhiflow.upms.common.dto.TenantInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 逐租户定时任务的租户清单：<b>查询失败 ≠ 0 个租户</b>（r3 followup C1，2026-10-06）。
 *
 * <p>{@code TenantApi} 经传输路由后，gRPC 传输失败回的是 {@code R.failed(data=null)}（REST 时代同样的故障是抛异常）。
 * 原先十几个任务都写 {@code tenantApi.list(..).getData()} 再 {@code if (tenants == null) return;}：失败被当成空清单，
 * 本轮一个租户都没跑，任务切面（{@code ScheduledJobMetricsAspect}）却记 {@code success}——结算、奖励发放、退款收口
 * 整轮静默跳过，监控上看不出来。
 *
 * <p>这里统一判定：失败（含无响应、业务拒绝、本地熔断 / 限流）打 WARN 并抛 {@link JobFailedException}，
 * 本轮跳过、任务指标记 {@code failure}；查询成功而为空才是真的没有租户。与 REST 时代「upms 不可达 ⇒ 任务抛异常」同口径。
 * mall-admin 主代码里不许再出现裸的 {@code .list(FROM_IN).getData()}（{@code ScheduledTenantsTest} 门禁）。
 */
@Slf4j
public final class ScheduledTenants {

	private ScheduledTenants() {
	}

	/**
	 * @param result {@code tenantApi.list(SecurityConstants.FROM_IN)} 的返回
	 * @param job    任务名（进日志与异常信息）
	 * @return 本轮要跑的租户；查询成功而为空时是空列表
	 * @throws JobFailedException 查询失败：本轮不跑任何租户
	 */
	public static List<TenantInfo> listOrFail(R<List<TenantInfo>> result, String job) {
		if (result == null || !Boolean.TRUE.equals(result.isOk())) {
			String detail = result == null ? "无响应" : "errCode=" + result.getErrCode() + ", msg=" + result.getMsg();
			log.warn("【{}】租户清单查询失败，本轮跳过（不当成 0 个租户）: {}", job, detail);
			throw new JobFailedException(job + " 租户清单查询失败，本轮跳过: " + detail);
		}
		return result.getData() == null ? List.of() : result.getData();
	}

}
