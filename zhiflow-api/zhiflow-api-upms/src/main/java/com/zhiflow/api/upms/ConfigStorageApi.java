package com.zhiflow.api.upms;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.upms.common.dto.StorageCredentials;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

/**
 * 存储配置服务 API 接口定义
 *
 */
@HttpExchange(url = "/upms", accept = "application/json", contentType = "application/json")
public interface ConfigStorageApi {

	/**
	 * 查询存储配置
	 *
	 * @param from 调用标志
	 * @return R
	 */
	@GetExchange("/configstorage/inside")
	R<StorageCredentials> getSysConfigStorage(@RequestHeader(SecurityConstants.FROM) String from);
}
