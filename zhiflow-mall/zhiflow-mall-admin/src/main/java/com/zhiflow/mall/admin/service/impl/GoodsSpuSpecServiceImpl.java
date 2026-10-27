/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.mall.common.entity.GoodsSpuSpec;
import com.zhiflow.mall.common.entity.GoodsSpec;
import com.zhiflow.mall.admin.mapper.GoodsSpuSpecMapper;
import com.zhiflow.mall.admin.service.GoodsSpuSpecService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * spu规格
 *
 * @date 2019-08-13 16:56:46
 */
@Service
@RequiredArgsConstructor
public class GoodsSpuSpecServiceImpl extends ServiceImpl<GoodsSpuSpecMapper, GoodsSpuSpec> implements GoodsSpuSpecService {

	private final GoodsDetailCacheEviction goodsDetailCacheEviction;

	/** 规格树变了：清所属商品的详情缓存（tenant_id 为空时按当前租户，理由同 GoodsSkuServiceImpl#save）。 */
	@Override
	public boolean save(GoodsSpuSpec entity) {
		boolean saved = super.save(entity);
		Map<String, String> spus = new LinkedHashMap<>();
		spus.put(entity.getSpuId(), entity.getTenantId());
		goodsDetailCacheEviction.evictAfterCommit(spus);
		return saved;
	}

	/** 改之前的商品和改之后的商品都清（请求里可能把关联行挪到了别的商品）。 */
	@Override
	public boolean updateById(GoodsSpuSpec entity) {
		Map<String, String> spus = goodsDetailCacheEviction.ofSpuSpecs(Collections.singletonList(entity.getId()));
		boolean updated = super.updateById(entity);
		spus.putAll(goodsDetailCacheEviction.ofSpuSpecs(Collections.singletonList(entity.getId())));
		goodsDetailCacheEviction.evictAfterCommit(spus);
		return updated;
	}

	@Override
	public boolean removeById(Serializable id) {
		Map<String, String> spus = goodsDetailCacheEviction.ofSpuSpecs(List.of(String.valueOf(id)));
		boolean removed = super.removeById(id);
		goodsDetailCacheEviction.evictAfterCommit(spus);
		return removed;
	}

	@Override
	public List<GoodsSpec> tree(GoodsSpuSpec goodsSpuSpec) {
		return baseMapper.selectTree(goodsSpuSpec.getSpuId());
	}
}
