package com.zhiflow.weixin.admin.local;

import com.zhiflow.api.weixin.WxUserApi;
import com.zhiflow.api.weixin.dto.WxMpUserInsideDTO;
import com.zhiflow.api.weixin.dto.WxUserInsideDTO;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.internal.InternalCallContext;
import com.zhiflow.weixin.admin.controller.WxUserController;
import com.zhiflow.weixin.common.dto.LoginDTO;
import com.zhiflow.weixin.common.dto.WxOpenDataDTO;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link WxUserApi} 的进程内实现（由 contract→controller 的「HTTP 方法 + 路径」映射生成）。
 *
 * <p><b>{@code getPhoneNumber} 的受检异常</b>：controller 抛 {@code WxErrorException}（受检），
 * 而契约方法不声明它 —— 这里捕获后包成 {@code IllegalStateException}（unchecked）抛出。
 * 依据：HTTP 路径下该异常
 * 被被调方 {@code GlobalBizExceptionHandler} 变成 500，调用方拿到的是 RestClient 的 unchecked
 * 异常；全仓无一处 catch 具体异常类型 ⇒ 包成 unchecked 与今天逐字等价。
 * ⚠️ 不能用 {@code IllegalArgumentException} / {@code BizException} 做包装 ——
 * 它们在 {@code GlobalBizExceptionHandler} 各有专用 handler，会改变响应体与状态码语义；
 * {@code IllegalStateException} 没有专用 handler，落进 {@code Exception.class} 兜底，与今天一致。
 *
 * <p>四条硬约束由门禁 {@code ContractLocalImplGateTest} 守：{@code @Primary}、
 * {@code REQUIRES_NEW}、{@code InternalCallContext}、以及**必须待在该契约目标服务的模块里**
 * （同一条路径在两个服务里都可能存在，放错模块可能变成调回自己）。
 */
@Primary
@Component
public class WxUserLocalApi implements WxUserApi {

	private final WxUserController wxUserController;

	/**
	 * ⚠️ 显式构造器 + {@code @Lazy}：controller 会牵出 service → mapper → {@code sqlSessionFactory}
	 * 一整条链，急切注入会把它提前到容器早期、引爆本仓既有的循环引用
	 * （2026-08-16 实测 upms 因此起不来并自动回滚）。**单测直接 new 所以测不到这一类。**
	 * 不用 {@code @RequiredArgsConstructor}：本仓没配 Lombok 的 {@code copyableAnnotations}，
	 * 字段上的 {@code @Lazy} 不会复制到生成的构造器参数上。
	 */
	public WxUserLocalApi(@Lazy WxUserController wxUserController) {
		this.wxUserController = wxUserController;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<WxUserInsideDTO> code2WxUserMp(LoginDTO loginDTO, String from) {
		return InternalCallContext.call(() -> this.wxUserController.code2WxUserMp(loginDTO));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<String> code2OpenIdMp(LoginDTO loginDTO, String from) {
		return InternalCallContext.call(() -> this.wxUserController.code2OpenIdMp(loginDTO));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<WxUserInsideDTO> bindOpenIdMp(LoginDTO loginDTO, String from) {
		return InternalCallContext.call(() -> this.wxUserController.bindOpenIdMp(loginDTO));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<WxMpUserInsideDTO> wxUserMp(LoginDTO loginDTO, String from) {
		return InternalCallContext.call(() -> this.wxUserController.wxUserMp(loginDTO));
	}

	/** 契约 {@code GET /wxuser/inside/{id}} → controller {@code getByIdInside}（不是 {@code getById} —— 那是 {@code GET /wxuser/{id}} 的后台端点）。 */
	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<WxUserInsideDTO> getById(String id, String from) {
		return InternalCallContext.call(() -> this.wxUserController.getByIdInside(id));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<List<WxUserInsideDTO>> getByUnionIdInside(String unionId, String wxUserId, String from) {
		return InternalCallContext.call(() -> this.wxUserController.getByUnionIdInside(unionId, wxUserId));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<WxUserInsideDTO> getByUnionIdInside(String unionId, String from) {
		return InternalCallContext.call(() -> this.wxUserController.getByUnionIdInside(unionId));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<WxUserInsideDTO> updateByMpInside(WxOpenDataDTO wxOpenDataDTO, String from) {
		return InternalCallContext.call(() -> this.wxUserController.updateByMpInside(wxOpenDataDTO));
	}

	/** 契约 {@code POST /wxuser/inside} → controller {@code saveOrUpdateInside}（不是 {@code save} —— 那是 {@code POST /wxuser} 的后台端点）。 */
	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<WxUserInsideDTO> saveOrUpdate(WxUserInsideDTO wxUser, String from) {
		return InternalCallContext.call(() -> this.wxUserController.saveOrUpdateInside(wxUser));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<WxUserInsideDTO> getByMallUserId(String mallUserId, String appId, String from) {
		return InternalCallContext.call(() -> this.wxUserController.getByMallUserId(mallUserId, appId));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<List<WxUserInsideDTO>> getListByUnionIdInside(String unionId, String from) {
		return InternalCallContext.call(() -> this.wxUserController.getListByUnionIdInside(unionId));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<List<WxUserInsideDTO>> getListByUserIdInside(String userId, String from) {
		return InternalCallContext.call(() -> this.wxUserController.getListByUserIdInside(userId));
	}
}
