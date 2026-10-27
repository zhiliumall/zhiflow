package com.zhiflow.upms.admin.local;

import com.zhiflow.api.upms.RoleApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.internal.InternalCallContext;
import com.zhiflow.upms.admin.controller.SysRoleController;
import com.zhiflow.upms.common.dto.RoleDataScope;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * {@link RoleApi} 的进程内实现。
 *
 * <p>由 contract→controller 的「HTTP 方法 + 路径」映射生成：实参**按 controller 的形参顺序**取，
 * 先按名字、名字对不上再按「同类型且唯一」——两者都不成立就不生成，宁可留空也不赌顺序
 * （同类型多参数传反了编译不报错，只会在生产上表现为查错了对象）。
 *
 * <p>三条硬约束由门禁 {@code ContractLocalImplGateTest} 守：{@code @Primary}、
 * {@code REQUIRES_NEW}、{@code InternalCallContext}。完整理由见 {@link ConfigStorageLocalApi}。
 */
@Primary
@Component
public class RoleLocalApi implements RoleApi {

	private final SysRoleController sysRoleController;

	/**
	 * ⚠️ 显式构造器 + {@code @Lazy}，两者都不能省。
	 *
	 * <p>controller 会牵出 service → mapper → {@code sqlSessionFactory} 一整条链。本类是
	 * {@code @Component}，**急切注入会把这条链提前到容器早期**，引爆本仓<b>既有的</b>循环引用 ——
	 * 2026-08-16 实测：12 个本地实现一起上线后 upms 起不来，
	 * {@code BeanCurrentlyInCreationException: 'sqlSessionFactory'}，流水线健康检查判死自动回滚。
	 * 单测直接 {@code new} 所以完全测不到这一类。
	 *
	 * <p>不用 {@code @RequiredArgsConstructor}：本仓没配 Lombok 的 {@code copyableAnnotations}，
	 * 写在字段上的 {@code @Lazy} 不会被复制到生成的构造器参数上，等于没写。
	 */
	public RoleLocalApi(@Lazy SysRoleController sysRoleController) {
		this.sysRoleController = sysRoleController;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<RoleDataScope> getDsTypeMinRole(List<String> roleIdList, String from) {
		return InternalCallContext.call(() -> this.sysRoleController.getDsTypeMinRole(roleIdList));
	}
}
