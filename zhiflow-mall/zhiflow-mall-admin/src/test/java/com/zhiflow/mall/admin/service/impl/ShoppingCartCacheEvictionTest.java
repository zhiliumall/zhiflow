/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.admin.mapper.GoodsSkuMapper;
import com.zhiflow.mall.admin.mapper.GoodsSkuSpecValueMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpecValueMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpuMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpuSpecMapper;
import com.zhiflow.mall.admin.mapper.ShoppingCartMapper;
import com.zhiflow.mall.common.entity.ShoppingCart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台清购物车缓存：先一条 DISTINCT 查出购物车里有这些商品 / 店铺 / 行的用户，再按<b>购物车行的租户</b>逐个清
 * （不看操作人当前的租户上下文），事务里登记的清除提交后才执行；商品类写入口经
 * {@link GoodsDetailCacheEviction#evictAfterCommit(Map)} 顺带走到这里。
 */
class ShoppingCartCacheEvictionTest {

	private final ShoppingCartMapper cartMapper = mock(ShoppingCartMapper.class);

	private final TenantPrefixedCacheManager cacheManager = new TenantPrefixedCacheManager();

	private final ShoppingCartCacheEviction eviction = new ShoppingCartCacheEviction(cacheManager, cartMapper);

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("改商品：清购物车里有这些商品的用户，按购物车行的租户清，别的用户不动")
	void evictsOwnersOfSpus() {
		seed("1", "u1", "u2", "u3");
		seed("2", "u1");
		when(cartMapper.selectOwnersBySpuIds(List.of("p1", "p2"))).thenReturn(List.of(owner("1", "u1"), owner("1", "u2")));
		TenantContextHolder.setTenantId("-1");

		eviction.evictCartsOfSpus(List.of("p1", "p2"));

		assertNull(cart("1").get("u1"));
		assertNull(cart("1").get("u2"));
		assertNotNull(cart("1").get("u3"));
		assertNotNull(cart("2").get("u1"), "别的租户的同一 userId 不动");
	}

	@Test
	@DisplayName("事务里：提交后才清；空集合不查库")
	void evictsAfterCommitAndSkipsEmpty() {
		seed("1", "u1");
		when(cartMapper.selectOwnersBySpuIds(List.of("p1"))).thenReturn(List.of(owner("1", "u1")));
		TransactionSynchronizationManager.initSynchronization();

		eviction.evictCartsOfSpus(List.of("p1"));
		eviction.evictCartsOfSpus(List.of());

		assertNotNull(cart("1").get("u1"), "提交前不清");
		for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
			sync.afterCommit();
		}
		assertNull(cart("1").get("u1"));
		verify(cartMapper).selectOwnersBySpuIds(List.of("p1"));
	}

	@Test
	@DisplayName("改店铺：清购物车里有该店铺商品的用户")
	void evictsOwnersOfShop() {
		seed("1", "u1", "u2");
		when(cartMapper.selectOwnersByShopId("s1")).thenReturn(List.of(owner("1", "u2")));

		eviction.evictCartsOfShop("s1");

		assertNotNull(cart("1").get("u1"));
		assertNull(cart("1").get("u2"));
	}

	@Test
	@DisplayName("商品类写入口统一出口 GoodsDetailCacheEviction#evictAfterCommit 会按受影响商品清购物车")
	void goodsDetailEvictionAlsoEvictsCarts() {
		ShoppingCartCacheEviction carts = mock(ShoppingCartCacheEviction.class);
		GoodsDetailCacheEviction goods = new GoodsDetailCacheEviction(cacheManager, mock(GoodsSpuMapper.class),
				mock(GoodsSkuMapper.class), mock(GoodsSpuSpecMapper.class), mock(GoodsSkuSpecValueMapper.class),
				mock(GoodsSpecValueMapper.class), carts);
		Map<String, String> spus = new LinkedHashMap<>();
		spus.put("p1", "1");
		spus.put("p2", "1");

		goods.evictAfterCommit(spus);

		verify(carts).evictCartsOfSpus(spus.keySet());
	}

	@Test
	@DisplayName("没有受影响的商品：不查购物车")
	void noSpusNoQuery() {
		eviction.evictCartsOfSpus(null);
		eviction.evictCartsOfShop(null);
		eviction.evictCartsOfRows(List.of());

		verify(cartMapper, never()).selectOwnersBySpuIds(any());
		verify(cartMapper, never()).selectOwnersByShopId(any());
		verify(cartMapper, never()).selectOwnersByIds(any());
	}

	private void seed(String tenantId, String... userIds) {
		for (String userId : userIds) {
			cart(tenantId).put(userId, "snapshot");
		}
	}

	private Cache cart(String tenantId) {
		return cacheManager.raw(tenantId + ":" + CacheConstants.MALL_SHOPPING_CART_CACHE);
	}

	private static ShoppingCart owner(String tenantId, String userId) {
		ShoppingCart row = new ShoppingCart();
		row.setTenantId(tenantId);
		row.setUserId(userId);
		return row;
	}

	/** 与 RedisAutoCacheManager#getCache 同一条规则：非 public: 缓存名加 {tenantId}: 前缀，没有租户不加。 */
	static class TenantPrefixedCacheManager extends ConcurrentMapCacheManager {

		@Override
		public Cache getCache(String name) {
			String tenantId = TenantContextHolder.getTenantId();
			if (name.startsWith(CacheConstants.PUBLIC_CACHE) || tenantId == null) {
				return super.getCache(name);
			}
			return super.getCache(tenantId + ":" + name);
		}

		Cache raw(String fullName) {
			return super.getCache(fullName);
		}
	}
}
