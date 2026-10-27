/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.mall.api.service.PayConfigService;
import com.zhiflow.pay.common.vo.PayConfigVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 支付配置API控制器
 *
 * @date 2025-01-19
 */
@Slf4j
@ApiLogin(mustLogin = false)
@RestController
@AllArgsConstructor
@RequestMapping("/payconfig")
@Tag(description = "payconfig", name = "支付配置API")
public class PayConfigController {

	private final PayConfigService payConfigService;

	/**
	 * 查询收款配置列表（不包含敏感字段）
	 * @return R
	 */
	@Operation(summary = "查询收款配置列表")
	@GetMapping("/list")
	public R<List<PayConfigVO>> getPayConfigList(
			@RequestParam(value = "appId", required = false) String appId) {
		log.debug("查询收款配置列表，appId={}", appId);
		return R.ok(payConfigService.getPayConfigList(appId));
	}

	/**
	 * 根据支付类型查询支付配置（不包含敏感字段）
	 * @param type 支付类型
	 * @return R
	 */
	@Operation(summary = "根据支付类型查询支付配置")
	@GetMapping("/bytype/{type}")
	public R<PayConfigVO> getPayConfigByType(@PathVariable("type") String type) {
		log.debug("根据支付类型查询支付配置，type={}", type);
		PayConfigVO vo = payConfigService.getPayConfigByType(type);
		if (vo == null) {
			return R.failed("支付配置不存在");
		}
		return R.ok(vo);
	}
}
