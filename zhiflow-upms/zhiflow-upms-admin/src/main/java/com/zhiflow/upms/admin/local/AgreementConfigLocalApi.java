package com.zhiflow.upms.admin.local;

import com.zhiflow.api.upms.AgreementConfigApi;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.internal.InternalCallContext;
import com.zhiflow.upms.admin.controller.AgreementConfigController;
import com.zhiflow.upms.common.dto.AgreementInfo;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AgreementConfigApi} 的进程内实现（1 个方法）。
 *
 * <p>当初被跳过的原因：handler {@code getByTypeInside} 包级私有（同 ShopInfoApi / OrganApi 一类）。
 * 已补 {@code public}，对 MVC 恒等变换。
 */
@Primary
@Component
public class AgreementConfigLocalApi implements AgreementConfigApi {

	private final AgreementConfigController agreementConfigController;

	/** ⚠️ 显式构造器 + {@code @Lazy}（理由同其余实现）。 */
	public AgreementConfigLocalApi(@Lazy AgreementConfigController agreementConfigController) {
		this.agreementConfigController = agreementConfigController;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<AgreementInfo> getByType(String agreementType, String from) {
		return InternalCallContext.call(() -> this.agreementConfigController.getByTypeInside(agreementType));
	}

}
