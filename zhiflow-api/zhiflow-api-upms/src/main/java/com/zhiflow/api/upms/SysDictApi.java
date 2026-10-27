package com.zhiflow.api.upms;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.upms.common.dto.SysDictInfo;
import com.zhiflow.upms.common.dto.SysDictQuery;
import com.zhiflow.upms.common.dto.SysDictValueInfo;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

import java.util.List;

/**
 * 字典服务 API 接口定义
 *
 */
@HttpExchange(url = "/upms", accept = "application/json", contentType = "application/json")
public interface SysDictApi {

	/**
	 * 查询字典列表
	 *
	 * @param sysDict 字典查询条件
	 * @param from    调用标志
	 * @return R
	 */
	@PostExchange("/dict/inside/page")
	R<List<SysDictInfo>> getSysDictList(@RequestBody SysDictQuery sysDict,
								   @RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 根据类型查询字典值
	 *
	 * @param type 字典类型
	 * @param from 调用标志
	 * @return R
	 */
	@GetExchange("/dict/inside/type/{type}")
	R<List<SysDictValueInfo>> getInsideDictByType(@PathVariable("type") String type,
											 @RequestHeader(SecurityConstants.FROM) String from);
}
