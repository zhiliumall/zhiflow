/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.api.mapper.GoodsSkuMapper;
import com.zhiflow.mall.api.mapper.GoodsSkuSpecValueMapper;
import com.zhiflow.mall.api.mapper.GoodsSpuMapper;
import com.zhiflow.mall.api.service.GoodsSpuSpecService;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpu;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GoodsDetailCache} 在真实的 Spring 缓存切面下的行为：有租户才缓存；查不到不缓存；
 * 未命中时 SQL 条数与 SKU 数无关（规格值一次批量查）。
 */
class GoodsDetailCacheTest {

	private final GoodsSpuMapper spuMapper = mock(GoodsSpuMapper.class);

	private final GoodsSkuMapper skuMapper = mock(GoodsSkuMapper.class);

	private final GoodsSkuSpecValueMapper specValueMapper = mock(GoodsSkuSpecValueMapper.class);

	private final GoodsSpuSpecService spuSpecService = mock(GoodsSpuSpecService.class);

	@AfterEach
	void clearTenant() {
		TenantContextHolder.clear();
	}

	@Test
	@DisplayName("拿不到租户：不走缓存，每次都查库（不会写出不带租户前缀的键）")
	void noTenantNoCache() {
		try (AnnotationConfigApplicationContext ctx = context()) {
			GoodsDetailCache cache = ctx.getBean(GoodsDetailCache.class);
			when(spuMapper.selectStockBySpuId("100")).thenReturn(new GoodsStockSnapshot());

			cache.getStock("100");
			cache.getStock("100");

			verify(spuMapper, times(2)).selectStockBySpuId("100");
			CacheManager cacheManager = ctx.getBean(CacheManager.class);
			for (String name : cacheManager.getCacheNames()) {
				assertEquals(0, ((Map<?, ?>) cacheManager.getCache(name).getNativeCache()).size(), "无租户时一个键都不能写：" + name);
			}
		}
	}

	@Test
	@DisplayName("有租户：第二次命中缓存，不再查库")
	void tenantPresentCaches() {
		try (AnnotationConfigApplicationContext ctx = context()) {
			TenantContextHolder.setTenantId("1");
			GoodsDetailCache cache = ctx.getBean(GoodsDetailCache.class);
			when(spuMapper.selectStockBySpuId("100")).thenReturn(new GoodsStockSnapshot());
			stubDetail();

			cache.getStock("100");
			cache.getStock("100");
			cache.getDetail("100");
			cache.getDetail("100");

			verify(spuMapper, times(1)).selectStockBySpuId("100");
			verify(spuMapper, times(1)).selectById2("100");
		}
	}

	@Test
	@DisplayName("商品查不到：返回 null 且不缓存（下次照常查库）")
	void missingGoodsNotCached() {
		try (AnnotationConfigApplicationContext ctx = context()) {
			TenantContextHolder.setTenantId("1");
			GoodsDetailCache cache = ctx.getBean(GoodsDetailCache.class);

			assertNull(cache.getDetail("404"));
			assertNull(cache.getDetail("404"));

			verify(spuMapper, times(2)).selectById2("404");
			verify(skuMapper, never()).listDetailSkuBySpuId(any());
		}
	}

	@Test
	@DisplayName("未命中：商品 1 + SKU 1 + 规格值批量 1 + 规格树，与 SKU 数无关")
	void missQueriesDoNotGrowWithSkuCount() {
		try (AnnotationConfigApplicationContext ctx = context()) {
			TenantContextHolder.setTenantId("1");
			stubDetail();

			GoodsDetailSnapshot snapshot = ctx.getBean(GoodsDetailCache.class).getDetail("100");

			assertEquals(3, snapshot.getSkus().size());
			assertEquals(1, snapshot.getSkus().get(0).getSpecs().size());
			verify(specValueMapper, times(1)).listGoodsSkuSpecValueBySkuIds(List.of("201", "202", "203"));
			verify(spuSpecService, times(1)).tree(any());
		}
	}

	private void stubDetail() {
		GoodsSpu spu = new GoodsSpu();
		spu.setId("100");
		when(spuMapper.selectById2("100")).thenReturn(spu);
		List<GoodsSku> skus = List.of(sku("201"), sku("202"), sku("203"));
		when(skuMapper.listDetailSkuBySpuId("100")).thenReturn(skus);
		GoodsSkuSpecValue value = new GoodsSkuSpecValue();
		value.setSkuId("201");
		value.setSpecValueName("红");
		when(specValueMapper.listGoodsSkuSpecValueBySkuIds(anyList())).thenReturn(List.of(value));
		when(spuSpecService.tree(any())).thenReturn(List.of());
	}

	private static GoodsSku sku(String id) {
		GoodsSku sku = new GoodsSku();
		sku.setId(id);
		return sku;
	}

	private AnnotationConfigApplicationContext context() {
		AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
		ctx.registerBean(GoodsSpuMapper.class, () -> spuMapper);
		ctx.registerBean(GoodsSkuMapper.class, () -> skuMapper);
		ctx.registerBean(GoodsSkuSpecValueMapper.class, () -> specValueMapper);
		ctx.registerBean(GoodsSpuSpecService.class, () -> spuSpecService);
		ctx.register(CachingConfig.class, GoodsDetailCache.class, GoodsStockCache.class);
		ctx.refresh();
		return ctx;
	}

	@Configuration
	@EnableCaching(proxyTargetClass = true)
	static class CachingConfig {

		@Bean
		CacheManager cacheManager() {
			return new ConcurrentMapCacheManager();
		}
	}
}
