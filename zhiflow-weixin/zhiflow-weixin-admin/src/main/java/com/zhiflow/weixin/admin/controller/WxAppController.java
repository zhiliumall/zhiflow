/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.weixin.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.log.error.ErrorLogRecorder;
import com.zhiflow.api.weixin.dto.WxAppInsideDTO;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.weixin.admin.service.WxAppService;
import com.zhiflow.weixin.common.entity.WxApp;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 微信账号配置
 *
 * @date 2019-03-23 21:26:35
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/wxapp")
@Tag(description = "wxapp", name = "微信账号配置")
public class WxAppController {

	private final WxAppService wxAppService;

	/**
	 * 吞掉的异常必须显式留痕（S12 L4）：本类有 10 处 catch 后把异常转成响应、不再抛出，
	 * 全局异常处理器看不到它们，DB 里查不到，只在容器日志里、重启即失。
	 */
	private final ErrorLogRecorder errorLogRecorder;

	/**
	 * 分页查询
	 *
	 * @param page  分页对象
	 * @param wxApp 微信账号配置
	 * @return
	 */
	@Operation(summary = "分页查询")
	@GetMapping("/page")
	@PreAuthorize("@ato.hasAuthority('wxmp:wxapp:index')")
	public R getWxAppPage(Page page, WxApp wxApp) {
		return R.ok(wxAppService.page(page, Wrappers.query(wxApp)));
	}

	/**
	 * 通过id查询微信账号配置
	 *
	 * @param id
	 * @return R
	 */
	@Operation(summary = "通过id查询微信账号配置")
	@GetMapping("/{id}")
//	@PreAuthorize("@ato.hasAuthority('wxmp:wxapp:get')")
	public R getById(@PathVariable("id") String id){
		WxApp wxApp = wxAppService.getById(id);
		if(wxApp == null){
			return R.ok();
		}
		// 脱敏：不向前端返回公众号密钥
		wxApp.setSecret(null);
		wxApp.setToken(null);
		wxApp.setAesKey(null);
		return R.ok(wxApp);
	}

	/**
	 * 新增微信账号配置
	 *
	 * @param wxApp 微信账号配置
	 * @return R
	 */
	@Operation(summary = "新增微信账号配置")
	@SysLog("新增微信账号配置")
	@PostMapping
	@PreAuthorize("@ato.hasAuthority('wxmp:wxapp:add')")
	public R save(@RequestBody WxApp wxApp) {
		return R.ok(wxAppService.save(wxApp));
	}

	/**
	 * 修改微信账号配置
	 *
	 * @param wxApp 微信账号配置
	 * @return R
	 */
	@Operation(summary = "修改微信账号配置")
	@SysLog("修改微信账号配置")
	@PutMapping
	@PreAuthorize("@ato.hasAuthority('wxmp:wxapp:edit')")
	public R updateById(@RequestBody WxApp wxApp) {
		wxAppService.updateById(wxApp);
		return R.ok();
	}

	/**
	 * 通过id查询微信账号配置
	 *
	 * @param id
	 * @return R
	 */
	@Operation(summary = "通过id查询微信账号配置")
	@Inside
	@GetMapping("/inside/{id}")
	public R<WxAppInsideDTO> getByIdInside(@PathVariable("id") String id) {
		WxApp wxApp = wxAppService.findByAppId(id);
		if(wxApp != null ){
			// [B7] 这三行保留：投影里本来就没有它们，置空是为了「万一有人把实体
			// 直接用在别处」时仍然不出仓 —— 双保险，不是重复劳动。
			wxApp.setAesKey(null);
			wxApp.setSecret(null);
			wxApp.setToken(null);
		}
		return R.ok(WxInsideProjections.toDto(wxApp));
	}
}
