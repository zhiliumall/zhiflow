/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.log.context.LogRecordContext;
import com.zhiflow.mall.common.dto.ImpactVO;
import com.zhiflow.mall.common.entity.GoodsCategory;
import com.zhiflow.mall.admin.service.EntityImpactService;
import com.zhiflow.mall.admin.service.GoodsCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 商品类目
 *
 * @date 2019-08-12 11:46:28
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/goodscategory")
@Tag(description = "goodscategory", name = "商品类目管理")
public class GoodsCategoryController {

    private final GoodsCategoryService goodsCategoryService;

    private final EntityImpactService entityImpactService;

    /**
    * 删除影响面预览（只读）。权限码与 removeById 一致：能删才能看。
    * @param id 商品类目id
    * @return R<ImpactVO>
    */
    @Operation(summary = "通过id查询删除影响面")
    @GetMapping("/{id}/impact")
    @PreAuthorize("@ato.hasAuthority('mall:goodscategory:del')")
    public R<ImpactVO> impact(@PathVariable String id) {
        return R.ok(entityImpactService.goodsCategoryImpact(id));
    }

    /**
    * 分页查询
    * @param page 分页对象
    * @param goodsCategory 商品类目
    * @return
    */
	@Operation(summary = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@ato.hasAuthority('mall:goodscategory:index')")
    public R getGoodsCategoryPage(Page page, GoodsCategory goodsCategory) {
        return R.ok(goodsCategoryService.page(page,Wrappers.query(goodsCategory)));
    }

	/**
	 *  返回树形集合
	 * @return
	 */
	@Operation(summary = "返回树形集合")
	@GetMapping("/tree")
	public R getGoodsCategoryTree() {
		return R.ok(goodsCategoryService.selectTree(null));
	}

    /**
    * 通过id查询商品类目
    * @param id
    * @return R
    */
	@Operation(summary = "通过id查询商品类目")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:goodscategory:get')")
    public R getById(@PathVariable("id") String id){
        return R.ok(goodsCategoryService.getById(id));
    }

    /**
    * 新增商品类目
    * @param goodsCategory 商品类目
    * @return R
    */
	@Operation(summary = "新增商品类目")
    @SysLog("新增商品类目")
    @PostMapping
    @PreAuthorize("@ato.hasAuthority('mall:goodscategory:add')")
    public R save(@RequestBody GoodsCategory goodsCategory){
        return R.ok(goodsCategoryService.save(goodsCategory));
    }

    /**
    * 修改商品类目
    * @param goodsCategory 商品类目
    * @return R
    */
	@Operation(summary = "修改商品类目")
    @SysLog("修改商品类目")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('mall:goodscategory:edit')")
    public R updateById(@RequestBody GoodsCategory goodsCategory){
    	if(goodsCategory.getId().equals(goodsCategory.getParentId())){
			return R.failed("不能将本级设为父类");
		}
        return R.ok(goodsCategoryService.updateById(goodsCategory));
    }

    /**
    * 通过id删除商品类目
    * @param id
    * @return R
    */
	@Operation(summary = "通过id删除商品类目")
    @SysLog(value = "删除商品类目",
            content = "'删除商品分类「' + #category.name + '」，其下有 ' + #impact.total + ' 项关联'")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:goodscategory:del')")
    public R removeById(@PathVariable String id){
        LogRecordContext.put("category", goodsCategoryService.getById(id));
        LogRecordContext.put("impact", entityImpactService.goodsCategoryImpact(id));
        return R.ok(goodsCategoryService.removeById(id));
    }
}
