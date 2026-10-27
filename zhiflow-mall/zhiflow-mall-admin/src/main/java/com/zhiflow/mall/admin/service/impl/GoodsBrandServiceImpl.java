/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.mall.common.entity.GoodsBrand;
import com.zhiflow.mall.admin.mapper.GoodsBrandMapper;
import com.zhiflow.mall.admin.service.GoodsBrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.io.Serializable;

/**
 * 商品品牌
 *
 * <p>改、删之后清用户端的品牌缓存（{@code mall:goods_brand#1800}，按 id；读方是 mall-api GoodsBrandServiceImpl#getById），
 * 按品牌行的 tenant_id 解析缓存，有事务时推迟到提交后。
 *
 * @date 2024-09-02 10:31:53
 */
@Service
@RequiredArgsConstructor
public class GoodsBrandServiceImpl extends ServiceImpl<GoodsBrandMapper, GoodsBrand> implements GoodsBrandService {

	private final CacheManager cacheManager;

	/** 新品牌在用户端缓存里还没有键（查不到的品牌不缓存），不用清。 */
	@Override
	public boolean save(GoodsBrand entity) {
		return super.save(entity);
	}

	@Override
	public boolean updateById(GoodsBrand entity) {
		boolean updated = super.updateById(entity);
		evictAfterCommit(entity.getId() == null ? null : baseMapper.selectById(entity.getId()));
		return updated;
	}

	@Override
	public boolean removeById(Serializable id) {
		// 删之前查：软删之后就查不到品牌行（和它的 tenant_id）了
		GoodsBrand row = baseMapper.selectById(id);
		boolean removed = super.removeById(id);
		evictAfterCommit(row);
		return removed;
	}

	private void evictAfterCommit(GoodsBrand row) {
		if (row != null) {
			AfterCommitCacheEviction.evictForTenant(cacheManager, row.getTenantId(), CacheConstants.MALL_GOODS_BRAND_CACHE, row.getId());
		}
	}
}
