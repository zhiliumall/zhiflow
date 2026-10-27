package com.zhiflow.api.upms;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.upms.common.dto.RoleDataScope;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

import java.util.List;

/**
 * 角色服务 API 接口定义
 *
 */
@HttpExchange(url = "/upms", accept = "application/json", contentType = "application/json")
public interface RoleApi {

	/**
	 * 通过角色ID查询权限类型最小的角色
	 *
	 * @param roleIdList 角色ID列表
	 * @param from       调用标志
	 * @return R
	 */
	@PostExchange("/role/dstype/min")
	R<RoleDataScope> getDsTypeMinRole(@RequestBody List<String> roleIdList,
								@RequestHeader(SecurityConstants.FROM) String from);
}
