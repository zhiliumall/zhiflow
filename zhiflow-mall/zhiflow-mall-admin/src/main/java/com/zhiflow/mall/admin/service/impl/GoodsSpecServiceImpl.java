/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.mall.admin.mapper.GoodsSpecValueMapper;
import com.zhiflow.mall.common.entity.GoodsSpec;
import com.zhiflow.mall.admin.mapper.GoodsSpecMapper;
import com.zhiflow.mall.admin.service.GoodsSpecService;
import com.zhiflow.mall.common.entity.GoodsSpecValue;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 规格
 *
 * @date 2019-08-13 16:10:54
 */
@Service
@AllArgsConstructor
public class GoodsSpecServiceImpl extends ServiceImpl<GoodsSpecMapper, GoodsSpec> implements GoodsSpecService {

	private final GoodsSpecValueMapper goodsSpecValueMapper;
	private final GoodsDetailCacheEviction goodsDetailCacheEviction;

	/** 新建的规格还没有商品用到，不涉及商品详情缓存。 */
	@Override
	public boolean save(GoodsSpec entity) {
		return super.save(entity);
	}

	/** 规格改名会出现在用到它的每个商品的规格树里：按引用它的商品逐个清。 */
	@Override
	public boolean updateById(GoodsSpec entity) {
		boolean updated = super.updateById(entity);
		goodsDetailCacheEviction.evictAfterCommit(goodsDetailCacheEviction.ofSpecs(Collections.singletonList(entity.getId())));
		return updated;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public boolean removeById(Serializable id) {
		// 删之前查：规格值随规格一起软删，删完就查不到哪些商品用过它们了
		Map<String, String> spus = goodsDetailCacheEviction.ofSpecs(List.of(String.valueOf(id)));
		goodsSpecValueMapper.delete(Wrappers.<GoodsSpecValue>update().lambda()
				.eq(GoodsSpecValue::getSpecId, id));
		boolean removed = super.removeById(id);
		goodsDetailCacheEviction.evictAfterCommit(spus);
		return removed;
	}
}
