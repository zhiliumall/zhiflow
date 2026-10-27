/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.mall.common.entity.GoodsCategory;
import com.zhiflow.mall.common.entity.GoodsCategoryTree;
import com.zhiflow.mall.admin.mapper.GoodsCategoryMapper;
import com.zhiflow.mall.admin.service.GoodsCategoryService;
import com.zhiflow.upms.common.util.TreeUtil;
import lombok.AllArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品类目
 *
 * @date 2019-08-12 11:46:28
 */
@Service
@AllArgsConstructor
public class GoodsCategoryServiceImpl extends ServiceImpl<GoodsCategoryMapper, GoodsCategory> implements GoodsCategoryService {

	private final CacheManager cacheManager;

	@Override
	public List<GoodsCategoryTree> selectTree(GoodsCategory goodsCategory) {
		return getTree(this.list(Wrappers.lambdaQuery(goodsCategory)));
	}

	/**
	 * 优化的商品分类树查询方法
	 * 使用高性能的树形构建算法，时间复杂度从 O(n²) 优化到 O(n)
	 *
	 * @param goodsCategory 查询条件
	 * @return 商品分类树列表（与selectTree返回完全一致）
	 */
	@Override
	public List<GoodsCategoryTree> selectTreeOptimized(GoodsCategory goodsCategory) {
		return getTreeOptimized(this.list(Wrappers.lambdaQuery(goodsCategory)));
	}

	/**
	 * 构建树（原版本）
	 *
	 * @param entitys
	 * @return
	 */
	private List<GoodsCategoryTree> getTree(List<GoodsCategory> entitys) {
		List<GoodsCategoryTree> treeList = entitys.stream()
				.filter(entity -> !entity.getId().equals(entity.getParentId()))
				.sorted(Comparator.comparingInt(GoodsCategory::getSort))
				.map(entity -> {
					GoodsCategoryTree node = new GoodsCategoryTree();
					BeanUtil.copyProperties(entity,node);
					return node;
				}).collect(Collectors.toList());
		return TreeUtil.build(treeList, CommonConstants.PARENT_ID);
	}

	/**
	 * 构建树（优化版本）
	 * 使用高性能算法，时间复杂度从 O(n²) 优化到 O(n)
	 *
	 * @param entitys 商品分类列表
	 * @return 商品分类树列表
	 */
	private List<GoodsCategoryTree> getTreeOptimized(List<GoodsCategory> entitys) {
		if (entitys == null) {
			return new ArrayList<>();
		}

		List<GoodsCategoryTree> treeList = entitys.stream()
				.filter(entity -> !entity.getId().equals(entity.getParentId()))
				.sorted(Comparator.comparingInt(GoodsCategory::getSort))
				.map(entity -> {
					GoodsCategoryTree node = new GoodsCategoryTree();
					BeanUtil.copyProperties(entity,node);
					return node;
				}).collect(Collectors.toList());

		// 使用优化的构建方法
		return TreeUtil.buildOptimized(treeList, CommonConstants.PARENT_ID);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public boolean removeById(Serializable id) {
		// 删之前查：软删之后就查不到分类行（和它的 tenant_id）了
		GoodsCategory row = baseMapper.selectById(id);
		super.removeById(id);
		remove(Wrappers.<GoodsCategory>query()
				.lambda().eq(GoodsCategory::getParentId, id));
		evictTreeAfterCommit(row == null ? null : row.getTenantId());
		return true;
	}

	/** 刚插入的行 tenant_id 由租户插件写进 SQL、实体上可能为空，为空时按当前租户清——插件写进去的正是当前租户。 */
	@Override
	public boolean save(GoodsCategory entity) {
		boolean saved = super.save(entity);
		evictTreeAfterCommit(entity.getTenantId());
		return saved;
	}

	@Override
	public boolean updateById(GoodsCategory entity) {
		boolean updated = super.updateById(entity);
		GoodsCategory row = entity.getId() == null ? null : baseMapper.selectById(entity.getId());
		evictTreeAfterCommit(row == null ? entity.getTenantId() : row.getTenantId());
		return updated;
	}

	/** 清用户端分类树缓存（mall:goods_category#1800::tree，读方是 mall-api GoodsCategoryServiceImpl#enabledTree）。 */
	private void evictTreeAfterCommit(String tenantId) {
		AfterCommitCacheEviction.evictForTenant(cacheManager, tenantId, CacheConstants.MALL_GOODSCATEGORY_CACHE, "tree");
	}

}
