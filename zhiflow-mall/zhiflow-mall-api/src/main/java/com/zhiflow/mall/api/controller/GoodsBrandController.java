/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.mall.common.entity.GoodsBrand;
import com.zhiflow.mall.api.service.GoodsBrandService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

/**
 * 商品品牌
 *
 * @date 2024-09-02 10:45:39
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/goodsbrand")
@Tag(description = "goodsbrand", name = "商品品牌管理")
public class GoodsBrandController {

    private final GoodsBrandService goodsBrandService;

    /**
     * 商品品牌list
     * @param goodsBrand 商品品牌
     * @return
     */
    @Operation(summary = "商品品牌list")
    @GetMapping("/list")
    public R getList(GoodsBrand goodsBrand) {
        return R.ok(goodsBrandService.list(Wrappers.query(goodsBrand)));
    }


    /**
     * 商品品牌分页列表
     * @param page 分页对象
     * @param goodsBrand 商品品牌
     * @return
     */
    @Operation(summary = "商品品牌分页列表")
    @ApiLogin(mustLogin = false)
    @GetMapping("/page")
    public R getPage(Page page, GoodsBrand goodsBrand) {
        return R.ok(goodsBrandService.page(page, Wrappers.query(goodsBrand)));
    }

    /**
     * 商品品牌查询
     * @param id
     * @return R
     */
    @Operation(summary = "商品品牌查询")
    @GetMapping("/{id}")
    public R getById(@PathVariable("id") String id) {
        return R.ok(goodsBrandService.getById(id));
    }

}
