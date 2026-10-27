package com.zhiflow.api.upms;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.upms.common.dto.SysLogLoginPayload;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

/**
 * 登录日志服务 API 接口定义
 *
 */
@HttpExchange(url = "/upms", accept = "application/json", contentType = "application/json")
public interface LogLoginApi {

	/**
	 * 保存登录日志
	 *
	 * @param sysLogLogin 日志实体
	 * @param from        是否内部调用
	 * @return R
	 */
	@PostExchange("/loglogin/save")
	R<Boolean> saveLogLogin(@RequestBody SysLogLoginPayload sysLogLogin,
						   @RequestHeader(SecurityConstants.FROM) String from);
}
