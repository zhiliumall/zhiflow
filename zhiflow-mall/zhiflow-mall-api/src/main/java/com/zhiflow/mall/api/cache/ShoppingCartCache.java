/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.mall.api.mapper.ShoppingCartMapper;
import com.zhiflow.mall.common.entity.ShoppingCart;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户整份购物车的缓存（{@code {tenantId}:mall:shopping_cart#600::{userId}}，值是 {@link ShoppingCartSnapshot}）。
 *
 * <p>只缓存不带筛选参数的默认查询（{@link #isWholeCart}）；命中时 0 条 SQL，未命中时 1 条（{@link #load}，四表 LEFT JOIN）。
 * 空购物车也缓存。拿不到租户时不走缓存（{@link CacheConstants#TENANT_PRESENT}）。
 *
 * <p>失效：用户端写购物车的入口（加购、改、删、下单删已购行）经 {@link #evictAfterCommit} 在提交后清该用户
 * （契约测试 {@code ShoppingCartCacheEvictContractTest}）；后台改商品、SKU、规格、店铺后，按购物车里有这些商品 /
 * 店铺的用户逐个清（mall-admin {@code ShoppingCartCacheEviction}）。下单扣库存、支付、取消<b>不清</b>：
 * 购物车里的库存、销量最多晚 10 分钟，下单校验永远以数据库为准。
 */
@Service
@RequiredArgsConstructor
public class ShoppingCartCache {

	private final ShoppingCartMapper shoppingCartMapper;
	private final CacheManager cacheManager;

	/**
	 * 这次查询是不是「某个用户的整份购物车」：列表 SQL 里只有 userId、shopId 两个条件，
	 * 带了 shopId 就是筛选，不走缓存（与分类树只在无筛选参数时缓存同理）。
	 */
	public static boolean isWholeCart(ShoppingCart query) {
		return StrUtil.isNotBlank(query.getUserId()) && StrUtil.isBlank(query.getShopId());
	}

	/** 该用户的整份购物车。 */
	@Cacheable(value = CacheConstants.MALL_SHOPPING_CART_CACHE, key = "#userId", condition = CacheConstants.TENANT_PRESENT)
	public ShoppingCartSnapshot getCart(String userId) {
		ShoppingCart query = new ShoppingCart();
		query.setUserId(userId);
		return ShoppingCartSnapshot.of(load(shoppingCartMapper, query));
	}

	/**
	 * 列表查库：四表 LEFT JOIN 带出商品、店铺、规格（见 ShoppingCartMapper#selectList2Optimized），
	 * 下架、删除的商品照样返回，由 H5 归到「失效商品」。缓存未命中与带筛选参数的查询共用这一份。
	 * 成本价查询里就不取；这里再置空一次，防止以后有人往查询里加回去（全局 ObjectMapper 后台也在用，不能靠 Mixin 屏蔽）。
	 */
	public static List<ShoppingCart> load(ShoppingCartMapper mapper, ShoppingCart query) {
		List<ShoppingCart> rows = mapper.selectList2Optimized(query).stream()
				.filter(row -> row.getGoodsSpu() != null)
				.toList();
		rows.forEach(row -> {
			if (row.getGoodsSku() != null) {
				row.getGoodsSku().setCostPrice(null);
			}
		});
		return rows;
	}

	/**
	 * 事务提交后清这个用户的购物车缓存（没有事务时当场清）。
	 *
	 * @param tenantId 购物车行的 tenant_id；为空时按当前租户上下文
	 */
	public void evictAfterCommit(String tenantId, String userId) {
		if (StrUtil.isBlank(userId)) {
			return;
		}
		AfterCommitCacheEviction.evictForTenant(cacheManager, tenantId, CacheConstants.MALL_SHOPPING_CART_CACHE, userId);
	}
}
