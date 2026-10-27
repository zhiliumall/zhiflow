/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.api.cache.GoodsCategoryTreeSnapshot;
import com.zhiflow.mall.common.entity.GoodsCategory;

import java.util.List;

/**
 * 商品类目
 *
 * @date 2019-08-12 11:46:28
 */
public interface GoodsCategoryService extends IService<GoodsCategory> {

	/**
	 * 启用的分类整棵树（走缓存），值是快照，转成接口返回值见 {@link GoodsCategoryTreeSnapshot#toJson}
	 *
	 * @return 根节点列表
	 */
	List<GoodsCategoryTreeSnapshot> enabledTree();

	/**
	 * 查询类目树（优化版本 - 使用 O(n) 算法）
	 * 使用高性能的 HashMap 索引构建树形结构
	 *
	 * @param goodsCategory 查询条件
	 * @return 树形结构列表
	 */
	List<JSONObject> selectTreeOptimized(GoodsCategory goodsCategory);

	/**
	 * 查询二级分类及商品（优化版本 - 解决 N+1 查询问题）
	 * 使用批量查询替代循环查询，将数据库查询次数从 1+N 降低到 2 次
	 *
	 * @param goodsCategory 查询条件
	 * @return 二级分类及关联商品列表
	 */
	List<GoodsCategory> twoCategoryOptimized(GoodsCategory goodsCategory);
}
