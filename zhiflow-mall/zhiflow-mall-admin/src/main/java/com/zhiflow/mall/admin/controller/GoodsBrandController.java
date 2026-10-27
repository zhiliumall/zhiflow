/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.mall.common.entity.GoodsBrand;
import com.zhiflow.mall.admin.service.GoodsBrandService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

/**
 * 商品品牌
 *
 * @date 2024-09-02 10:31:53
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
    @GetMapping("/page")
    @PreAuthorize("@ato.hasAuthority('mall:goodsbrand:index')")
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
    @PreAuthorize("@ato.hasAuthority('mall:goodsbrand:get')")
    public R getById(@PathVariable("id") String id) {
        return R.ok(goodsBrandService.getById(id));
    }

    /**
     * 商品品牌新增
     * @param goodsBrand 商品品牌
     * @return R
     */
    @Operation(summary = "商品品牌新增")
    @SysLog("新增商品品牌")
    @PostMapping
    @PreAuthorize("@ato.hasAuthority('mall:goodsbrand:add')")
    public R save(@RequestBody GoodsBrand goodsBrand) {
        return R.ok(goodsBrandService.save(goodsBrand));
    }

    /**
     * 商品品牌修改
     * @param goodsBrand 商品品牌
     * @return R
     */
    @Operation(summary = "商品品牌修改")
    @SysLog("修改商品品牌")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('mall:goodsbrand:edit')")
    public R updateById(@RequestBody GoodsBrand goodsBrand) {
        return R.ok(goodsBrandService.updateById(goodsBrand));
    }

    /**
     * 商品品牌删除
     * @param id
     * @return R
     */
    @Operation(summary = "商品品牌删除")
    @SysLog("删除商品品牌")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:goodsbrand:del')")
    public R removeById(@PathVariable String id) {
        return R.ok(goodsBrandService.removeById(id));
    }

}
