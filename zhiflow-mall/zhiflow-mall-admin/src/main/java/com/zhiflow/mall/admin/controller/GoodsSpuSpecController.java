/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.mall.common.entity.GoodsSpuSpec;
import com.zhiflow.mall.admin.service.GoodsSpuSpecService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * spu规格
 *
 * @date 2019-08-13 16:56:46
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/goodsspuspec")
@Tag(description = "goodsspuspec", name = "spu规格管理")
public class GoodsSpuSpecController {

    private final GoodsSpuSpecService goodsSpuSpecService;

    /**
    * 分页查询
    * @param page 分页对象
    * @param goodsSpuSpec spu规格
    * @return
    */
	@Operation(summary = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@ato.hasAuthority('mall:goodsspu:index')")
    public R getGoodsSpuSpecPage(Page page, GoodsSpuSpec goodsSpuSpec) {
        return R.ok(goodsSpuSpecService.page(page,Wrappers.query(goodsSpuSpec)));
    }

	/**
	 * 获取商品规格
	 * @param goodsSpuSpec
	 * @return
	 */
	@Operation(summary = "获取商品规格")
	@GetMapping("/tree")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:index')")
	public R getGoodsSpuSpecTree(GoodsSpuSpec goodsSpuSpec) {
		return R.ok(goodsSpuSpecService.tree(goodsSpuSpec));
	}

    /**
    * 通过id查询spu规格
    * @param id
    * @return R
    */
	@Operation(summary = "通过id查询spu规格")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:goodsspu:get')")
    public R getById(@PathVariable("id") String id){
        return R.ok(goodsSpuSpecService.getById(id));
    }

    /**
    * 新增spu规格
    * @param goodsSpuSpec spu规格
    * @return R
    */
	@Operation(summary = "新增spu规格")
    @SysLog("新增spu规格")
    @PostMapping
    @PreAuthorize("@ato.hasAuthority('mall:goodsspu:add')")
    public R save(@RequestBody GoodsSpuSpec goodsSpuSpec){
        return R.ok(goodsSpuSpecService.save(goodsSpuSpec));
    }

    /**
    * 修改spu规格
    * @param goodsSpuSpec spu规格
    * @return R
    */
	@Operation(summary = "修改spu规格")
    @SysLog("修改spu规格")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('mall:goodsspu:edit')")
    public R updateById(@RequestBody GoodsSpuSpec goodsSpuSpec){
        return R.ok(goodsSpuSpecService.updateById(goodsSpuSpec));
    }

    /**
    * 通过id删除spu规格
    * @param id
    * @return R
    */
	@Operation(summary = "通过id删除spu规格")
    @SysLog("删除spu规格")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:goodsspu:del')")
    public R removeById(@PathVariable String id){
        return R.ok(goodsSpuSpecService.removeById(id));
    }

}
