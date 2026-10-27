/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.test.MyBatisPlusTestSupport;
import com.zhiflow.mall.api.cache.ShoppingCartCache;
import com.zhiflow.mall.api.mapper.GoodsSpuMapper;
import com.zhiflow.mall.api.mapper.ShoppingCartMapper;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.ShoppingCart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户端每个写购物车的入口都清了「该行所属用户」的整车缓存，且是写库之后才登记（登记本身是提交后执行，
 * 见 {@code AfterCommitCacheEviction}）。下单删已购行走的就是 {@link ShoppingCartServiceImpl#remove}。
 *
 * <p>入口是否齐全由字节码扫描的 {@code ShoppingCartCacheEvictContractTest} 守；这里守「清的是谁」。
 */
class ShoppingCartWriteEvictsCacheTest {

	private final ShoppingCartMapper mapper = mock(ShoppingCartMapper.class);

	private final GoodsSpuMapper spuMapper = mock(GoodsSpuMapper.class);

	private final ShoppingCartCache cache = mock(ShoppingCartCache.class);

	@BeforeAll
	static void registerEntities() {
		MyBatisPlusTestSupport.register(ShoppingCart.class);
	}

	@AfterEach
	void clearTenant() {
		TenantContextHolder.clear();
	}

	@Test
	@DisplayName("加购：upsert 之后清当前用户")
	void saveEvictsUser() {
		TenantContextHolder.setTenantId("1");
		GoodsSpu spu = new GoodsSpu();
		spu.setShelf(MallConstants.UP);
		when(spuMapper.selectById("p1")).thenReturn(spu);
		ShoppingCart saved = new ShoppingCart();
		saved.setId("c1");
		saved.setQuantity(2);
		when(mapper.upsertAddQuantity(any())).thenReturn(saved);
		ShoppingCart entity = cart(null, "u1", "p1", "k1");
		entity.setQuantity(1);

		service().save(entity);

		var order = inOrder(mapper, cache);
		order.verify(mapper).upsertAddQuantity(any());
		order.verify(cache).evictAfterCommit("1", "u1");
	}

	@Test
	@DisplayName("改数量 / 换规格：更新之后清该行所属用户")
	void updateEvictsOwner() {
		ShoppingCart existing = cart("c1", "u1", "p1", "k1");
		existing.setTenantId("1");
		when(mapper.selectById("c1")).thenReturn(existing);
		when(mapper.updateById(any(ShoppingCart.class))).thenReturn(1);
		ShoppingCart entity = cart("c1", "u1", null, "k2");
		entity.setQuantity(3);

		service().updateById(entity);

		var order = inOrder(mapper, cache);
		order.verify(mapper).updateById(any(ShoppingCart.class));
		order.verify(cache).evictAfterCommit("1", "u1");
	}

	@Test
	@DisplayName("条件删除（购物车删除 / 下单删已购行）：删之后按被删行的用户清，同一用户只清一次，不多查")
	void removeByWrapperEvictsOwnersOnce() {
		ShoppingCart a = cart("c1", "u1", "p1", "k1");
		a.setTenantId("1");
		ShoppingCart b = cart("c2", "u1", "p2", "k2");
		b.setTenantId("1");
		when(mapper.selectList(any())).thenReturn(List.of(a, b));

		service().remove(Wrappers.<ShoppingCart>lambdaQuery().eq(ShoppingCart::getUserId, "u1"));

		var order = inOrder(mapper, cache);
		order.verify(mapper).physicalRemoveByIds(List.of("c1", "c2"));
		order.verify(cache, times(1)).evictAfterCommit("1", "u1");
		verify(mapper, times(1)).selectList(any());
	}

	@Test
	@DisplayName("条件删除没删到行：不清")
	void removeNothingEvictsNothing() {
		when(mapper.selectList(any())).thenReturn(List.of());

		service().remove(Wrappers.<ShoppingCart>lambdaQuery().eq(ShoppingCart::getUserId, "u1"));

		verify(cache, never()).evictAfterCommit(any(), anyString());
	}

	@Test
	@DisplayName("按 id 删：删之前查出行的用户，删之后清")
	void removeByIdsEvictsOwners() {
		ShoppingCart u1 = cart(null, "u1", null, null);
		u1.setTenantId("1");
		ShoppingCart u2 = cart(null, "u2", null, null);
		u2.setTenantId("1");
		when(mapper.selectList(any())).thenReturn(List.of(u1, u2));

		service().removeByIds(List.of("c1", "c2"));

		var order = inOrder(mapper, cache);
		order.verify(mapper).selectList(any());
		order.verify(mapper).physicalRemoveByIds(List.of("c1", "c2"));
		order.verify(cache).evictAfterCommit("1", "u1");
		order.verify(cache).evictAfterCommit("1", "u2");
	}

	@Test
	@DisplayName("按单个 id 删：同上")
	void removeByIdEvictsOwner() {
		ShoppingCart u1 = cart(null, "u1", null, null);
		u1.setTenantId("1");
		when(mapper.selectList(any())).thenReturn(List.of(u1));
		when(mapper.physicalRemoveById("c1")).thenReturn(1);

		service().removeById("c1");

		var order = inOrder(mapper, cache);
		order.verify(mapper).physicalRemoveById("c1");
		order.verify(cache).evictAfterCommit("1", "u1");
	}

	private ShoppingCartServiceImpl service() {
		IdentifierGenerator ids = mock(IdentifierGenerator.class);
		when(ids.nextId(any())).thenReturn(42L);
		ShoppingCartServiceImpl service = new ShoppingCartServiceImpl(spuMapper, ids, cache);
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		return service;
	}

	private static ShoppingCart cart(String id, String userId, String spuId, String skuId) {
		ShoppingCart cart = new ShoppingCart();
		cart.setId(id);
		cart.setUserId(userId);
		cart.setSpuId(spuId);
		cart.setSkuId(skuId);
		return cart;
	}
}
