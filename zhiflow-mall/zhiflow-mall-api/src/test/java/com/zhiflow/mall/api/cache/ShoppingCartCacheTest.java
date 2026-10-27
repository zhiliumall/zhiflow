/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.api.mapper.GoodsSpuMapper;
import com.zhiflow.mall.api.mapper.ShoppingCartMapper;
import com.zhiflow.mall.api.service.impl.ShoppingCartServiceImpl;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.ShoppingCart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ShoppingCartCache} 在真实的 Spring 缓存切面下的行为。缓存管理器按生产的规则给非 {@code public:} 缓存名
 * 加 {@code {tenantId}:} 前缀（见 {@code RedisAutoCacheManager#getCache}），以便验证租户、用户两层隔离。
 * <ul>
 * <li>命中时 0 次查库（每请求 0 条 SQL），未命中 1 次；</li>
 * <li>带 shopId 筛选不走缓存；拿不到租户不走缓存；</li>
 * <li>清某用户只清该租户下该用户那一个键，事务里登记的清除提交后才执行。</li>
 * </ul>
 */
class ShoppingCartCacheTest {

	private final ShoppingCartMapper mapper = mock(ShoppingCartMapper.class);

	@AfterEach
	void clearTenant() {
		TenantContextHolder.clear();
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("整份购物车：第二次命中缓存，不再查库；返回内容与查库相同（成本价为空）")
	void wholeCartHitsCache() {
		try (AnnotationConfigApplicationContext ctx = context()) {
			TenantContextHolder.setTenantId("1");
			stubCart("u1");
			ShoppingCartServiceImpl service = service(ctx);

			List<ShoppingCart> first = service.listCart(query("u1", null));
			List<ShoppingCart> second = service.listCart(query("u1", null));

			verify(mapper, times(1)).selectList2Optimized(any());
			assertEquals(first, second);
			assertEquals(1, second.size());
			assertEquals(new BigDecimal("10.00"), second.get(0).getGoodsSku().getSalesPrice());
			assertNull(second.get(0).getGoodsSku().getCostPrice());
			assertNotNull(cache(ctx, "1").get("u1"), "键是 {tenantId}:" + CacheConstants.MALL_SHOPPING_CART_CACHE + "::{userId}");
		}
	}

	@Test
	@DisplayName("带 shopId 筛选：不走缓存，每次查库，也不写键")
	void filteredQueryBypassesCache() {
		try (AnnotationConfigApplicationContext ctx = context()) {
			TenantContextHolder.setTenantId("1");
			stubCart("u1");
			ShoppingCartServiceImpl service = service(ctx);

			service.listCart(query("u1", "s1"));
			service.listCart(query("u1", "s1"));

			verify(mapper, times(2)).selectList2Optimized(any());
			assertTrue(nativeMap(ctx, "1").isEmpty());
		}
	}

	@Test
	@DisplayName("拿不到租户：不走缓存，每次查库（不会写出不带租户前缀的键）")
	void noTenantNoCache() {
		try (AnnotationConfigApplicationContext ctx = context()) {
			stubCart("u1");
			ShoppingCartCache cache = ctx.getBean(ShoppingCartCache.class);

			cache.getCart("u1");
			cache.getCart("u1");

			verify(mapper, times(2)).selectList2Optimized(any());
			CacheManager cacheManager = ctx.getBean(CacheManager.class);
			for (String name : cacheManager.getCacheNames()) {
				assertEquals(0, ((Map<?, ?>) cacheManager.getCache(name).getNativeCache()).size(), "无租户时一个键都不能写：" + name);
			}
		}
	}

	@Test
	@DisplayName("清除只动该租户下该用户的键：别的用户、别的租户的同一用户都不受影响")
	void evictIsPerTenantPerUser() {
		try (AnnotationConfigApplicationContext ctx = context()) {
			stubCart("u1");
			stubCart("u2");
			ShoppingCartCache cache = ctx.getBean(ShoppingCartCache.class);
			TenantContextHolder.setTenantId("1");
			cache.getCart("u1");
			cache.getCart("u2");
			TenantContextHolder.setTenantId("2");
			cache.getCart("u1");

			// 当前上下文是租户 2，按行的租户 1 清
			cache.evictAfterCommit("1", "u1");

			assertNull(cache(ctx, "1").get("u1"));
			assertNotNull(cache(ctx, "1").get("u2"));
			assertNotNull(cache(ctx, "2").get("u1"));
		}
	}

	@Test
	@DisplayName("事务里登记的清除提交后才执行；清完下一次重新查库")
	void evictRunsAfterCommit() {
		try (AnnotationConfigApplicationContext ctx = context()) {
			TenantContextHolder.setTenantId("1");
			stubCart("u1");
			ShoppingCartCache cache = ctx.getBean(ShoppingCartCache.class);
			cache.getCart("u1");

			TransactionSynchronizationManager.initSynchronization();
			cache.evictAfterCommit("1", "u1");
			assertNotNull(cache(ctx, "1").get("u1"), "提交前不清");
			for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
				sync.afterCommit();
			}
			TransactionSynchronizationManager.clearSynchronization();

			assertNull(cache(ctx, "1").get("u1"));
			cache.getCart("u1");
			verify(mapper, times(2)).selectList2Optimized(argThat(q -> "u1".equals(q.getUserId())));
		}
	}

	@Test
	@DisplayName("空购物车也缓存")
	void emptyCartIsCached() {
		try (AnnotationConfigApplicationContext ctx = context()) {
			TenantContextHolder.setTenantId("1");
			when(mapper.selectList2Optimized(any())).thenReturn(List.of());
			ShoppingCartServiceImpl service = service(ctx);

			assertTrue(service.listCart(query("nobody", null)).isEmpty());
			assertTrue(service.listCart(query("nobody", null)).isEmpty());

			verify(mapper, times(1)).selectList2Optimized(any());
			assertFalse(nativeMap(ctx, "1").isEmpty());
		}
	}

	private void stubCart(String userId) {
		ShoppingCart row = new ShoppingCart();
		row.setId("c-" + userId);
		row.setUserId(userId);
		GoodsSpu spu = new GoodsSpu();
		spu.setId("p1");
		spu.setPicUrls(new String[] { "https://img/a.png" });
		row.setGoodsSpu(spu);
		GoodsSku sku = new GoodsSku();
		sku.setId("k1");
		sku.setSalesPrice(new BigDecimal("10.00"));
		sku.setCostPrice(new BigDecimal("3.00"));
		row.setGoodsSku(sku);
		// 每次查库都返回新对象，模拟 MyBatis
		when(mapper.selectList2Optimized(argThat(q -> q != null && userId.equals(q.getUserId())))).thenAnswer(inv -> {
			ShoppingCart copy = ShoppingCartSnapshot.of(List.of(row)).toCarts().get(0);
			copy.getGoodsSku().setCostPrice(new BigDecimal("3.00"));
			return List.of(copy);
		});
	}

	private ShoppingCartServiceImpl service(AnnotationConfigApplicationContext ctx) {
		ShoppingCartServiceImpl service = new ShoppingCartServiceImpl(mock(GoodsSpuMapper.class), null,
				ctx.getBean(ShoppingCartCache.class));
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		return service;
	}

	private static ShoppingCart query(String userId, String shopId) {
		ShoppingCart query = new ShoppingCart();
		query.setUserId(userId);
		query.setShopId(shopId);
		return query;
	}

	private static Cache cache(AnnotationConfigApplicationContext ctx, String tenantId) {
		return ctx.getBean(TenantPrefixedCacheManager.class).raw(tenantId + ":" + CacheConstants.MALL_SHOPPING_CART_CACHE);
	}

	private static Map<?, ?> nativeMap(AnnotationConfigApplicationContext ctx, String tenantId) {
		return (Map<?, ?>) cache(ctx, tenantId).getNativeCache();
	}

	private AnnotationConfigApplicationContext context() {
		AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
		ctx.registerBean(ShoppingCartMapper.class, () -> mapper);
		ctx.register(CachingConfig.class, ShoppingCartCache.class);
		ctx.refresh();
		return ctx;
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

		/** 按完整名字取（断言用，不加前缀）。 */
		Cache raw(String fullName) {
			return super.getCache(fullName);
		}
	}

	@Configuration
	@EnableCaching(proxyTargetClass = true)
	static class CachingConfig {

		@Bean
		TenantPrefixedCacheManager cacheManager() {
			return new TenantPrefixedCacheManager();
		}
	}
}
