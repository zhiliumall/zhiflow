/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.mall.api.mapper.GoodsSpuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 商品库存与销量缓存 {@code {tenantId}:mall:goods_stock#5::{spuId}}（5 秒）。只由 {@link GoodsDetailCache#getStock} 调用，
 * 它在前面加了进程内单飞；单独成类是为了让 {@code @Cacheable} 经代理生效（同类自调用不走代理）。
 *
 * <p>不进进程内 L1（{@code RedisL1Settings#DENIED}）：它只靠 TTL 过期，代号标记感知不到。
 */
@Service
@RequiredArgsConstructor
public class GoodsStockCache {

	private final GoodsSpuMapper goodsSpuMapper;

	/** 商品不存在时返回 null，不缓存。 */
	@Cacheable(value = CacheConstants.MALL_GOODS_STOCK_CACHE, key = "#spuId",
			condition = CacheConstants.TENANT_PRESENT, unless = "#result == null")
	public GoodsStockSnapshot get(String spuId) {
		return goodsSpuMapper.selectStockBySpuId(spuId);
	}
}
