/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.mall.common.entity.GoodsSpecValue;
import com.zhiflow.mall.admin.mapper.GoodsSpecValueMapper;
import com.zhiflow.mall.admin.service.GoodsSpecValueService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 规格值
 *
 * @date 2019-08-13 16:11:05
 */
@Service
@RequiredArgsConstructor
public class GoodsSpecValueServiceImpl extends ServiceImpl<GoodsSpecValueMapper, GoodsSpecValue> implements GoodsSpecValueService {

	private final GoodsDetailCacheEviction goodsDetailCacheEviction;

	/** 新建的规格值还没有 SKU 用到，不涉及商品详情缓存。 */
	@Override
	public boolean save(GoodsSpecValue entity) {
		return super.save(entity);
	}

	/** 规格值改名会出现在用到它的 SKU 与规格树里：按 SKU 用到它的商品逐个清。 */
	@Override
	public boolean updateById(GoodsSpecValue entity) {
		boolean updated = super.updateById(entity);
		goodsDetailCacheEviction.evictAfterCommit(goodsDetailCacheEviction.ofSpecValues(Collections.singletonList(entity.getId())));
		return updated;
	}

	@Override
	public boolean removeById(Serializable id) {
		Map<String, String> spus = goodsDetailCacheEviction.ofSpecValues(List.of(String.valueOf(id)));
		boolean removed = super.removeById(id);
		goodsDetailCacheEviction.evictAfterCommit(spus);
		return removed;
	}
}
