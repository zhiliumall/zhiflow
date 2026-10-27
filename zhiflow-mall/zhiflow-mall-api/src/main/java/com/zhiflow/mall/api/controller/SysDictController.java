package com.zhiflow.mall.api.controller;

import com.zhiflow.api.upms.SysDictApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.upms.common.dto.SysDictInfo;
import com.zhiflow.upms.common.dto.SysDictQuery;
import com.zhiflow.upms.common.dto.SysDictValueInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 字典（只读，供 H5 取下拉选项）。
 */
@ApiLogin(mustLogin = false)
@RestController
@RequiredArgsConstructor
@RequestMapping("/dict")
@Tag(description = "dict", name = "字典管理模块")
public class SysDictController {

	private final SysDictApi sysDictApi;

	@Operation(summary = "字典列表")
	@PostMapping("/list")
	public R<List<SysDictInfo>> getListPage(@RequestBody SysDictQuery sysDict) {
		return sysDictApi.getSysDictList(sysDict, SecurityConstants.FROM_IN);
	}

	@Operation(summary = "按类型取字典项")
	@GetMapping("/type/{type}")
	public R<List<SysDictValueInfo>> getByType(@PathVariable("type") String type) {
		return sysDictApi.getInsideDictByType(type, SecurityConstants.FROM_IN);
	}
}
