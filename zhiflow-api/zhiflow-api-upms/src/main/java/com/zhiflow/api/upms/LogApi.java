package com.zhiflow.api.upms;

import com.zhiflow.upms.common.dto.SysLogPayload;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

/**
 * 日志服务 API 接口定义
 *
 */
@HttpExchange(url = "/upms", accept = "application/json", contentType = "application/json")
public interface LogApi {

	/**
	 * 保存日志
	 *
	 * @param sysLog 日志实体
	 * @param from   是否内部调用
	 * @return R
	 */
	@PostExchange("/log/save")
	R<Boolean> saveLog(@RequestBody SysLogPayload sysLog,
					  @RequestHeader(SecurityConstants.FROM) String from);
}
