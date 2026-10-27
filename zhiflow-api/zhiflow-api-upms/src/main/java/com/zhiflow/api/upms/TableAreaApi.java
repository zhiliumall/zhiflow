package com.zhiflow.api.upms;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.upms.common.dto.AreaInfo;
import com.zhiflow.upms.common.dto.AreaQuery;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

import java.util.List;

/**
 * 地区服务 API 接口定义
 *
 */
@HttpExchange(url = "/upms", accept = "application/json", contentType = "application/json")
public interface TableAreaApi {

	/**
	 * 地区表分页List(服务间调用)
	 *
	 * @param tableArea 查询条件
	 * @param from      调用标志
	 * @return R
	 */
	@PostExchange("/tablearea/inside/list")
	R<List<AreaInfo>> getList(@RequestBody AreaQuery tableArea,
							  @RequestHeader(SecurityConstants.FROM) String from);
}
