package com.zhiflow.api.upms;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.upms.common.dto.AgreementInfo;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

/**
 * 协议配置服务 API 接口定义
 *
 */
@HttpExchange(url = "/upms", accept = "application/json", contentType = "application/json")
public interface AgreementConfigApi {

	/**
	 * 通过类型查询协议
	 *
	 * @param agreementType 协议类型
	 * @param from          调用标志
	 * @return R
	 */
	@GetExchange("/agreementconfig/inside/{agreementType}")
	R<AgreementInfo> getByType(@PathVariable("agreementType") String agreementType,
								@RequestHeader(SecurityConstants.FROM) String from);
}
