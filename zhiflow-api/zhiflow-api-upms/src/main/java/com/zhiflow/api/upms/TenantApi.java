package com.zhiflow.api.upms;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.upms.common.dto.TenantInfo;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

import java.util.List;

/**
 * 租户服务 API 接口定义
 *
 */
@HttpExchange(url = "/upms", accept = "application/json", contentType = "application/json")
public interface TenantApi {

	/**
	 * 查询租户列表
	 *
	 * @param from 调用标志
	 * @return R
	 */
	@GetExchange("/tenant/inside/list")
	R<List<TenantInfo>> list(@RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 通过ID查询租户
	 *
	 * @param id   租户ID
	 * @param from 调用标志
	 * @return R
	 */
	@GetExchange("/tenant/inside/{id}")
	R<TenantInfo> getById(@PathVariable("id") String id,
						@RequestHeader(SecurityConstants.FROM) String from);
}
