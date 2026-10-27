/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.admin.controller;

import com.zhiflow.upms.admin.projection.UpmsInsideProjections;
import com.zhiflow.upms.common.dto.AgreementInfo;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.upms.common.entity.AgreementConfig;
import com.zhiflow.upms.admin.service.AgreementConfigService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

/**
 * 协议
 *
 * @date 2024-03-27 11:02:10
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/agreementconfig")
@Tag(description = "agreementconfig", name = "协议管理")
public class AgreementConfigController {

    private final AgreementConfigService agreementConfigService;

    /**
     * 协议分页列表
     * @param page 分页对象
     * @param agreementConfig 协议
     * @return
     */
    @Operation(summary = "协议分页列表")
    @GetMapping("/page")
    @PreAuthorize("@ato.hasAuthority('upms:agreementconfig:index')")
    public R getPage(Page page, AgreementConfig agreementConfig) {
        return R.ok(agreementConfigService.page(page, Wrappers.query(agreementConfig)));
    }

    /**
     * 协议查询
     * @param id
     * @return R
     */
    @Operation(summary = "协议查询")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('upms:agreementconfig:get')")
    public R getById(@PathVariable("id") String id) {
        return R.ok(agreementConfigService.getById(id));
    }

    /**
     * 协议新增
     * @param agreementConfig 协议
     * @return R
     */
    @Operation(summary = "协议新增")
    @SysLog("新增协议")
    @PostMapping
    @PreAuthorize("@ato.hasAuthority('upms:agreementconfig:add')")
    public R save(@RequestBody AgreementConfig agreementConfig) {
        try {
            return R.ok(agreementConfigService.save(agreementConfig));
        } catch (DuplicateKeyException e){
            if(e.getMessage().contains("uk_type")){
                return R.failed("该类型已存在，请不要重复添加");
            }else{
                return R.failed(e.getMessage());
            }
        } catch (Exception e) {
            log.error("Operation failed", e);
            return R.failed(e.getMessage());
        }
    }

    /**
     * 协议修改
     * @param agreementConfig 协议
     * @return R
     */
    @Operation(summary = "协议修改")
    @SysLog("修改协议")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('upms:agreementconfig:edit')")
    public R updateById(@RequestBody AgreementConfig agreementConfig) {
        return R.ok(agreementConfigService.updateById(agreementConfig));
    }

    /**
     * 协议删除
     * @param id
     * @return R
     */
    @Operation(summary = "协议删除")
    @SysLog("删除协议")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('upms:agreementconfig:del')")
    public R removeById(@PathVariable String id) {
        return R.ok(agreementConfigService.removeById(id));
    }

    /**
     * 通过协议类型，查询协议
     * @return R
     */
    @Operation(summary = "通过协议类型，查询协议")
    @GetMapping("/type/{agreementType}")
    R<AgreementConfig> getByType(@PathVariable("agreementType") String agreementType){
        return R.ok(agreementConfigService.getOne(Wrappers.<AgreementConfig>query().lambda()
                .eq(AgreementConfig::getAgreementType, agreementType)));
    }/**
     * 通过协议类型，查询协议（服务间调用）
     * @return R
     */
    @Operation(summary = "通过协议类型，查询协议（服务间调用）")
    @GetMapping("/inside/{agreementType}")
    @Inside
    // public：本地实现（AgreementConfigLocalApi）跨包直调。对 MVC 恒等变换。
    public R<AgreementInfo> getByTypeInside(@PathVariable("agreementType") String agreementType){
        return R.ok(UpmsInsideProjections.agreement(agreementConfigService.getOne(Wrappers.<AgreementConfig>query()
                .lambda().eq(AgreementConfig::getAgreementType, agreementType))));
    }
}
