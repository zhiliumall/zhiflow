/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.mall.common.entity.GoodsSpecValue;
import com.zhiflow.mall.admin.service.GoodsSpecValueService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 规格值
 *
 * @date 2019-08-13 16:11:05
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/goodsspecvalue")
@Tag(description = "goodsspecvalue", name = "规格值管理")
public class GoodsSpecValueController {

    private final GoodsSpecValueService goodsSpecValueService;

    /**
    * 分页查询
    * @param page 分页对象
    * @param goodsSpecValue 规格值
    * @return
    */
	@Operation(summary = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@ato.hasAuthority('mall:goodsspec:index')")
    public R getGoodsSpecValuePage(Page page, GoodsSpecValue goodsSpecValue) {
        return R.ok(goodsSpecValueService.page(page,Wrappers.query(goodsSpecValue)));
    }

	@Operation(summary = "list查询")
	@GetMapping("/list")
	// [R6 批三·读 2026-08-17] 补方法级鉴权，复用本模块既有读码（无新增权限码）。
	@PreAuthorize("@ato.hasAuthority('mall:goodsspec:index')")
	public R getGoodsSpecValueList(GoodsSpecValue goodsSpecValue) {
		return R.ok(goodsSpecValueService.list(Wrappers.query(goodsSpecValue)));
	}

    /**
    * 通过id查询规格值
    * @param id
    * @return R
    */
	@Operation(summary = "通过id查询规格值")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:goodsspec:get')")
    public R getById(@PathVariable("id") String id){
        return R.ok(goodsSpecValueService.getById(id));
    }

    /**
    * 新增规格值
    * @param goodsSpecValue 规格值
    * @return R
    */
	@Operation(summary = "新增规格值")
    @SysLog("新增规格值")
    @PostMapping
    // 2026-08-17：原为 'mall:goodsspu:index'（商品**列表查看**权），与 GoodsSpecController#save 同款走样。
    // 本类 page/get/edit/del 用的都是 mall:goodsspec:*（规格值没有独立权限码，一直与规格共用）。
    @PreAuthorize("@ato.hasAuthority('mall:goodsspec:add')")
    public R save(@RequestBody GoodsSpecValue goodsSpecValue){
		try {
			goodsSpecValueService.save(goodsSpecValue);
			return R.ok(goodsSpecValue);
		}catch (DuplicateKeyException e){
			if(e.getMessage().contains("uk_name")){
				return R.failed("该规格值已存在");
			}else{
				return R.failed("新增规格值失败，请稍后重试");
			}
		}
    }

    /**
    * 修改规格值
    * @param goodsSpecValue 规格值
    * @return R
    */
	@Operation(summary = "修改规格值")
    @SysLog("修改规格值")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('mall:goodsspec:edit')")
    public R updateById(@RequestBody GoodsSpecValue goodsSpecValue){
		try {
			return R.ok(goodsSpecValueService.updateById(goodsSpecValue));
		}catch (DuplicateKeyException e){
			if(e.getMessage().contains("uk_name")){
				return R.failed("该规格值已存在");
			}else{
				return R.failed("修改规格值失败，请稍后重试");
			}
		}

    }

    /**
    * 通过id删除规格值
    * @param id
    * @return R
    */
	@Operation(summary = "通过id删除规格值")
    @SysLog("删除规格值")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:goodsspec:del')")
    public R removeById(@PathVariable String id){
        return R.ok(goodsSpecValueService.removeById(id));
    }

}
