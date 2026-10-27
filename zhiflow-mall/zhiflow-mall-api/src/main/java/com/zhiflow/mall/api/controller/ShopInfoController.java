/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.mall.common.constant.MallReturnCode;
import com.zhiflow.mall.common.entity.ShopInfo;
import com.zhiflow.mall.api.service.ShopInfoService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

/**
 * 店铺表
 *
 * @date 2020-05-15 14:35:53
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
    @Operation(summary = "查询商城设置（名称、logo、客服电话）")
    @ApiLogin(mustLogin = false)
    @GetMapping("/{id}")
    public R getById(@PathVariable("id") String id) {
		ShopInfo shopInfo = shopInfoService.getById(id);
		if(shopInfo == null){
			return R.failed(MallReturnCode.ERR_80044.getCode(), MallReturnCode.ERR_80044.getMsg());
		}
        return R.ok(shopInfo);
    }
}
