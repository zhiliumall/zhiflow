package com.zhiflow.weixin.admin.local;

import com.zhiflow.api.weixin.WxAppApi;
import com.zhiflow.api.weixin.dto.WxAppInsideDTO;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.internal.InternalCallContext;
import com.zhiflow.weixin.admin.controller.WxAppController;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * {@link WxAppApi} 的进程内实现（由 contract→controller 的「HTTP 方法 + 路径」映射生成）。
 *
 * <p>四条硬约束由门禁 {@code ContractLocalImplGateTest} 守：{@code @Primary}、
 * {@code REQUIRES_NEW}、{@code InternalCallContext}、以及**必须待在该契约目标服务的模块里**
 * （同一条路径在两个服务里都可能存在，放错模块可能变成调回自己）。
 */
@Primary
@Component
public class WxAppLocalApi implements WxAppApi {

	private final WxAppController wxAppController;

	/**
	 * ⚠️ 显式构造器 + {@code @Lazy}：controller 会牵出 service → mapper → {@code sqlSessionFactory}
	 * 一整条链，急切注入会把它提前到容器早期、引爆本仓既有的循环引用
	 * （2026-08-16 实测 upms 因此起不来并自动回滚）。**单测直接 new 所以测不到这一类。**
	 * 不用 {@code @RequiredArgsConstructor}：本仓没配 Lombok 的 {@code copyableAnnotations}，
	 * 字段上的 {@code @Lazy} 不会复制到生成的构造器参数上。
	 */
	public WxAppLocalApi(@Lazy WxAppController wxAppController) {
		this.wxAppController = wxAppController;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<WxAppInsideDTO> getById(String id, String from) {
		return InternalCallContext.call(() -> this.wxAppController.getByIdInside(id));
	}
}
