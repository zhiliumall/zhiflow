/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.admin.controller;

import com.zhiflow.upms.admin.projection.UpmsInsideProjections;

import com.zhiflow.upms.common.dto.SysLogLoginPayload;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.upms.common.entity.SysLogLogin;
import com.zhiflow.upms.admin.service.SysLogLoginService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

/**
 * 登录日志
 *
 * @date 2020-03-28 16:39:04
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/loglogin")
@Tag(description = "loglogin", name = "登录日志管理")
public class SysLogLoginController {

    private final SysLogLoginService sysLogLoginService;

    /**
     * 分页列表
     * @param page 分页对象
     * @param sysLogLogin 登录日志
     * @return
     */
    @Operation(summary = "分页列表")
    @GetMapping("/page")
//    @PreAuthorize("@ato.hasAuthority('sys:loglogin:index')")
    public R getPage(Page page, SysLogLogin sysLogLogin) {
        return R.ok(sysLogLoginService.page(page, Wrappers.query(sysLogLogin)));
    }

    /**
     * 登录日志查询
     * @param id
     * @return R
     */
    @Operation(summary = "登录日志查询")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('sys:loglogin:get')")
    public R getById(@PathVariable("id") String id) {
        return R.ok(sysLogLoginService.getById(id));
    }

    /**
     * 登录日志新增
     * @param sysLogLogin 登录日志
     * @return R
     */
    @Operation(summary = "登录日志新增")
	@Inside
    @PostMapping("/save")
    public R<Boolean> save(@RequestBody SysLogLoginPayload sysLogLogin) {
        return R.ok(sysLogLoginService.save(UpmsInsideProjections.sysLogLogin(sysLogLogin)));
    }

    /**
     * 登录日志修改
     * @param sysLogLogin 登录日志
     * @return R
     */
    @Operation(summary = "登录日志修改")
    @SysLog("修改登录日志")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('sys:loglogin:edit')")
    public R updateById(@RequestBody SysLogLogin sysLogLogin) {
        return R.ok(sysLogLoginService.updateById(sysLogLogin));
    }

    /**
     * 登录日志删除
     * @param id
     * @return R
     */
    @Operation(summary = "登录日志删除")
    @SysLog("删除登录日志")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('sys:loglogin:del')")
    public R removeById(@PathVariable String id) {
        return R.ok(sysLogLoginService.removeById(id));
    }

}
