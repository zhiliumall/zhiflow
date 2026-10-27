/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import com.zhiflow.api.weixin.WxAppApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 微信应用
 *
 * @date 2019-08-25 15:39:39
 */
@Slf4j
@ApiLogin(mustLogin = false)
@RestController
@AllArgsConstructor
@RequestMapping("/wxapp")
@Tag(description = "wxapp", name = "微信应用API")
public class WxAppController {

	private final WxAppApi wxAppHttpClient;

	/**
	 * 通过id查询微信应用配置
	 *
	 * @param id
	 * @return R
	 */
	@Operation(summary = "通过id查询微信应用配置")
	@GetMapping("/{id}")
	public R getById(@PathVariable("id") String id) {
		return R.ok(wxAppHttpClient.getById(id, SecurityConstants.FROM_IN));
	}
}
