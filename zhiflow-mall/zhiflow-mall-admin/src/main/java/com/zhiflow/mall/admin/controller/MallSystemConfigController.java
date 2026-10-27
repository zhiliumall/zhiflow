/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.mall.admin.service.MallConfigService;
import com.zhiflow.mall.common.entity.MallConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 商城系统配置
 *
 * @date 2025-07-22
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/mallsystemconfig")
@Tag(description = "mallsystemconfig", name = "商城系统配置管理")
public class MallSystemConfigController {

    private final MallConfigService mallConfigService;

    /**
     * 配置项分页列表
     */
    @Operation(summary = "配置项分页列表")
    @GetMapping("/page")
    // [R6 批三·读 2026-08-17] 补方法级鉴权，复用本模块既有读码（无新增权限码）。
    @PreAuthorize("@ato.hasAuthority('mall:systemconfig:get')")
    public R getPage(Page page, MallConfig mallConfig) {
        return R.ok(mallConfigService.page(page, Wrappers.<MallConfig>lambdaQuery()
                .eq(MallConfig::getTenantId, TenantContextHolder.getTenantId())
                .eq(MallConfig::getDelFlag, CommonConstants.STATUS_NORMAL)
                .like(mallConfig.getConfigKey() != null, MallConfig::getConfigKey, mallConfig.getConfigKey())
                .like(mallConfig.getConfigDesc() != null, MallConfig::getConfigDesc, mallConfig.getConfigDesc())
                .orderByAsc(MallConfig::getConfigKey)));
    }

    /**
     * 获取所有配置项
     */
    @Operation(summary = "获取所有配置项")
    @GetMapping("/all")
    // [R6 批三·读 2026-08-17] 补方法级鉴权，复用本模块既有读码（无新增权限码）。
    @PreAuthorize("@ato.hasAuthority('mall:systemconfig:get')")
    public R getAllConfigs() {
        return R.ok(mallConfigService.getAllConfigs());
    }

    /**
     * 配置项查询
     */
    @Operation(summary = "配置项查询")
    @GetMapping("/{id}")
    // [R6 批三·读 2026-08-17] 补方法级鉴权，复用本模块既有读码（无新增权限码）。
    @PreAuthorize("@ato.hasAuthority('mall:systemconfig:get')")
    public R getById(@PathVariable("id") String id) {
        return R.ok(mallConfigService.getById(id));
    }

    /**
     * 配置项新增
     */
    @Operation(summary = "配置项新增")
    @SysLog("新增商城配置项")
    @PostMapping
    // [R6 批三 2026-08-17] 原先无任何 @PreAuthorize ⇒ 全部后台账号可调。系统配置写操作，该码 sys_menu 已存在。
    @PreAuthorize("@ato.hasAuthority('mall:systemconfig:add')")
    public R save(@RequestBody MallConfig mallConfig) {
        mallConfig.setTenantId(TenantContextHolder.getTenantId());
        mallConfig.setDelFlag(CommonConstants.STATUS_NORMAL);
        boolean saved = mallConfigService.save(mallConfig);
        // 新增也要清缓存：mall:config:{tid} 是整租户一份 Map（TTL 1h），不清则新键最长 1 小时读不到
        mallConfigService.clearCache();
        return R.ok(saved);
    }

    /**
     * 配置项修改
     */
    @Operation(summary = "配置项修改")
    @SysLog("修改商城配置项")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('mall:systemconfig:edit')")
    public R updateById(@RequestBody MallConfig mallConfig) {
        mallConfigService.updateById(mallConfig);
        // 清除缓存
        mallConfigService.clearCache();
        return R.ok();
    }

    /**
     * 批量更新配置
     */
    @Operation(summary = "批量更新配置")
    @SysLog("批量更新商城配置")
    @PutMapping("/batch")
    @PreAuthorize("@ato.hasAuthority('mall:systemconfig:edit')")
    public R updateBatch(@RequestBody Map<String, String> configs) {
        mallConfigService.updateConfigs(configs);
        return R.ok();
    }

    /**
     * 配置项删除
     */
    @Operation(summary = "配置项删除")
    @SysLog("删除商城配置项")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:systemconfig:del')")
    public R removeById(@PathVariable String id) {
        mallConfigService.removeById(id);
        // 清除缓存
        mallConfigService.clearCache();
        return R.ok();
    }

    /**
     * 刷新配置缓存
     */
    @Operation(summary = "刷新配置缓存")
    @SysLog("刷新商城配置缓存")
    @PostMapping("/refresh")
    // [R6 批三 2026-08-17] 原先无任何 @PreAuthorize ⇒ 全部后台账号可调。刷新缓存按写权处理：它会让全站配置立即换一套值。
    @PreAuthorize("@ato.hasAuthority('mall:systemconfig:edit')")
    public R refreshCache() {
        mallConfigService.clearCache();
        return R.ok("配置缓存已刷新");
    }
}
