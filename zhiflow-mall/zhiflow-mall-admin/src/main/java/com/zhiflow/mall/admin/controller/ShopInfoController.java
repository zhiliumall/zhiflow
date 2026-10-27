/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;

import com.zhiflow.mall.common.entity.ShopInfo;
import com.zhiflow.mall.admin.service.ShopInfoService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

/**
 * 店铺表
 *
 * @date 2020-05-15 13:35:18
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/shopinfo")
@Tag(description = "shopinfo", name = "商城设置")
public class ShopInfoController {

    private final ShopInfoService shopInfoService;

    /**
     * 店铺表查询
     * @param id
     * @return R
     */
    @Operation(summary = "查询商城设置")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:shopinfo:get','mall:orderinfo:edit')")
    public R getById(@PathVariable("id") String id) {
        return R.ok(shopInfoService.getById(id));
    }

    /**
     * 店铺表修改
     * @param shopInfo 店铺表
     * @return R
     */
    @Operation(summary = "修改商城设置（名称、logo、客服电话）")
    @SysLog("修改商城设置")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('mall:shopinfo:edit')")
    public R updateById(@Valid @RequestBody ShopInfo shopInfo) {
        return R.ok(shopInfoService.updateSettings(shopInfo));
    }
}
