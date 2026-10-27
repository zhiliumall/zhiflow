package com.zhiflow.upms.admin.local;

import com.zhiflow.api.upms.TenantApi;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.internal.InternalCallContext;
import com.zhiflow.upms.admin.projection.UpmsInsideProjections;
import com.zhiflow.upms.admin.service.SysTenantService;
import com.zhiflow.upms.common.dto.TenantInfo;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link TenantApi} 的进程内实现：开源版没有租户管理接口，直接读 sys_tenant。
 *
 * <p>没有 controller 可转调，但 {@code REQUIRES_NEW} 与 {@code InternalCallContext} 照样要：
 * 前者让读取不加入调用方事务，后者让被调链路上的 {@code @Inside} 判定看到内部调用。
 * 门禁见 {@code ContractLocalImplGateTest}。
 */
@Primary
@Component
public class TenantLocalApi implements TenantApi {

	private final SysTenantService sysTenantService;

	public TenantLocalApi(@Lazy SysTenantService sysTenantService) {
		this.sysTenantService = sysTenantService;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public R<List<TenantInfo>> list(String from) {
		return InternalCallContext.call(() -> R.ok(UpmsInsideProjections.tenants(sysTenantService.listInsideTenants())));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public R<TenantInfo> getById(String id, String from) {
		return InternalCallContext.call(() -> R.ok(UpmsInsideProjections.tenant(sysTenantService.getById(id))));
	}
}
