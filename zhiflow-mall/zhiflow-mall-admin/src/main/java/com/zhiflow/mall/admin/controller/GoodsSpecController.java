/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.mall.common.entity.GoodsSpec;
import com.zhiflow.mall.admin.service.GoodsSpecService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 规格
 *
 * @date 2019-08-13 16:10:54
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/goodsspec")
@Tag(description = "goodsspec", name = "规格管理")
public class GoodsSpecController {

    private final GoodsSpecService goodsSpecService;

    /**
    * 分页查询
    * @param page 分页对象
    * @param goodsSpec 规格
    * @return
    */
	@Operation(summary = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@ato.hasAuthority('mall:goodsspec:index')")
    public R getGoodsSpecPage(Page page, GoodsSpec goodsSpec) {
        return R.ok(goodsSpecService.page(page,Wrappers.query(goodsSpec)));
    }

	@Operation(summary = "list查询")
	@GetMapping("/list")
	// [R6 批三·读 2026-08-17] 补方法级鉴权，复用本模块既有读码（无新增权限码）。
	@PreAuthorize("@ato.hasAuthority('mall:goodsspec:index')")
	public R getGoodsSpecList(GoodsSpec goodsSpec) {
		return R.ok(goodsSpecService.list(Wrappers.query(goodsSpec)));
	}

    /**
    * 通过id查询规格
    * @param id
    * @return R
    */
	@Operation(summary = "通过id查询规格")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:goodsspec:get')")
    public R getById(@PathVariable("id") String id){
        return R.ok(goodsSpecService.getById(id));
    }

    /**
    * 新增规格
    * @param goodsSpec 规格
    * @return R
    */
	@Operation(summary = "新增规格")
    @SysLog("新增规格")
    @PostMapping
    // 2026-08-17：原为 'mall:goodsspu:index'（商品**列表查看**权），是本类里唯一一处走样 ——
    // page/get/edit/del 四个方法用的都是 mall:goodsspec:*。写操作挂读权限意味着
    // 「能看商品列表就能新建规格」。mall:goodsspec:add 在 sys_menu 已存在（无需新增菜单）。
    @PreAuthorize("@ato.hasAuthority('mall:goodsspec:add')")
    public R save(@RequestBody GoodsSpec goodsSpec){
		try {
			goodsSpecService.save(goodsSpec);
			return R.ok(goodsSpec);
		}catch (DuplicateKeyException e){
			if(e.getMessage().contains("uk_name")){
				return R.failed("该规格已存在");
			}else{
				return R.failed("新增规格失败，请稍后重试");
			}
		}
    }

    /**
    * 修改规格
    * @param goodsSpec 规格
    * @return R
    */
	@Operation(summary = "修改规格")
    @SysLog("修改规格")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('mall:goodsspec:edit')")
    public R updateById(@RequestBody GoodsSpec goodsSpec){
		try {
        	return R.ok(goodsSpecService.updateById(goodsSpec));
		}catch (DuplicateKeyException e){
			if(e.getMessage().contains("uk_name")){
				return R.failed("该规格已存在");
			}else{
				return R.failed("修改规格失败，请稍后重试");
			}
		}
    }

    /**
    * 通过id删除规格
    * @param id
    * @return R
    */
	@Operation(summary = "通过id删除规格")
    @SysLog("删除规格")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:goodsspec:del')")
    public R removeById(@PathVariable String id){
        return R.ok(goodsSpecService.removeById(id));
    }

}
