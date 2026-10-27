/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.GoodsCategory;
import com.zhiflow.mall.common.entity.GoodsCategoryTree;

import java.util.List;

/**
 * 商品类目
 *
 * @date 2019-08-12 11:46:28
 */
public interface GoodsCategoryService extends IService<GoodsCategory> {

	/**
	 * 查询类目树
	 *
	 * @param goodsCategory 查询条件
	 * @return 树
	 */
	List<GoodsCategoryTree> selectTree(GoodsCategory goodsCategory);

	/**
	 * 优化的商品分类树查询方法
	 * 使用高性能的树形构建算法，时间复杂度从 O(n²) 优化到 O(n)
	 *
	 * @param goodsCategory 查询条件
	 * @return 商品分类树列表（与selectTree返回完全一致）
	 */
	List<GoodsCategoryTree> selectTreeOptimized(GoodsCategory goodsCategory);
}
