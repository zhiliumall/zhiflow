package com.zhiflow.mall.api.metrics;

import com.zhiflow.api.upms.TenantApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.common.observability.BizMetrics;
import com.zhiflow.upms.common.dto.TenantInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 启动后把在册租户的 {@code order_create_total} 预注册为 0（「查不到指标」只能意味着埋点坏了）。
 *
 * <p>放到虚拟线程里做、失败只打 WARN：upms 恰好在重启窗口时取不到租户清单，损失的只是「为 0 时可见」，
 * 不值得拖住启动或让启动失败。
 */
@Slf4j
@Component
public class BizMetricsPreRegistrar {

	private final TenantApi tenantApi;

	public BizMetricsPreRegistrar(TenantApi tenantApi) {
		this.tenantApi = tenantApi;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		Thread.ofVirtual().name("biz-metrics-preregister").start(this::preRegister);
	}

	void preRegister() {
		try {
			R<List<TenantInfo>> r = tenantApi.list(SecurityConstants.FROM_IN);
			List<TenantInfo> tenants = r == null ? null : r.getData();
			if (tenants == null) {
				return;
			}
			BizMetrics.preRegister(tenants.stream().filter(Objects::nonNull).map(TenantInfo::getId).toList());
		}
		catch (Exception e) {
			log.warn("[业务指标] 预注册租户维度失败（只影响「为 0 时是否可见」）：{}", e.toString());
		}
	}
}
