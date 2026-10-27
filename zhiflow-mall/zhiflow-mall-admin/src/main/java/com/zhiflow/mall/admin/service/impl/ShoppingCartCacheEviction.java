/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.mall.admin.mapper.ShoppingCartMapper;
import com.zhiflow.mall.common.entity.ShoppingCart;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 后台改了会影响购物车展示的数据之后，清用户端整份购物车缓存（{@code mall:shopping_cart#600}，key 是 userId，
 * 读方见 mall-api {@code ShoppingCartCache}）。
 *
 * <p>购物车缓存按用户存，商品 / 店铺变了不知道是哪些用户的：先一条 {@code SELECT DISTINCT user_id, tenant_id}
 * 查出购物车里有这些商品（店铺）的用户，再逐个按 key 清（提交后；没有事务时当场清），按购物车行的 tenant_id 解析缓存。
 * 不用 allEntries（Spring Data Redis 里那是 KEYS）。
 *
 * <p>入口：商品、SKU、规格类的全部后台写入口都经 {@link GoodsDetailCacheEviction#evictAfterCommit(java.util.Map)}，
 * 在那里顺带调 {@link #evictCartsOfSpus}；店铺写入口调 {@link #evictCartsOfShop}；后台自己的购物车写入口调
 * {@link #evictCartsOfRows} / {@link #evictCart}。契约测试 {@code ShoppingCartCacheEvictContractTest} 守着。
 *
 * <p>查询在写事务里跑：商品 / 店铺写入口不改 shopping_cart，查询早一点晚一点结果相同。
 */
@Component
@RequiredArgsConstructor
public class ShoppingCartCacheEviction {

	private final CacheManager cacheManager;
	private final ShoppingCartMapper shoppingCartMapper;

	/** 购物车里有这些商品的用户。 */
	public void evictCartsOfSpus(Collection<String> spuIds) {
		List<String> ids = nonNull(spuIds);
		if (!ids.isEmpty()) {
			evictOwners(shoppingCartMapper.selectOwnersBySpuIds(ids));
		}
	}

	/** 购物车里有这个店铺商品的用户（开源版单店铺：等于该租户所有有购物车的用户；店铺设置极少改）。 */
	public void evictCartsOfShop(Object shopId) {
		if (shopId != null) {
			evictOwners(shoppingCartMapper.selectOwnersByShopId(String.valueOf(shopId)));
		}
	}

	/** 这些购物车行的用户。删除类操作必须在删之前调（删了就查不到了）。 */
	public void evictCartsOfRows(Collection<String> cartIds) {
		List<String> ids = nonNull(cartIds);
		if (!ids.isEmpty()) {
			evictOwners(shoppingCartMapper.selectOwnersByIds(ids));
		}
	}

	/** 已经知道用户时直接清，不查库。{@code tenantId} 为空时按当前租户上下文。 */
	public void evictCart(String tenantId, String userId) {
		if (StrUtil.isNotBlank(userId)) {
			AfterCommitCacheEviction.evictForTenant(cacheManager, tenantId, CacheConstants.MALL_SHOPPING_CART_CACHE, userId);
		}
	}

	/** 查询已按（用户, 租户）去重，每行清一次。 */
	private void evictOwners(List<ShoppingCart> owners) {
		owners.forEach(owner -> evictCart(owner.getTenantId(), owner.getUserId()));
	}

	private static List<String> nonNull(Collection<String> ids) {
		return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
	}
}
