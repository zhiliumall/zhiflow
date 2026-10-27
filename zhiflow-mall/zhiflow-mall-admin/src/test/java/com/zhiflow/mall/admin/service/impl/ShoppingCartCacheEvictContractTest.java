/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.common.test.WriteEvictScanner;
import com.zhiflow.mall.admin.mapper.GoodsSkuMapper;
import com.zhiflow.mall.admin.mapper.GoodsSkuSpecValueMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpecMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpecValueMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpuMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpuSpecMapper;
import com.zhiflow.mall.admin.mapper.ShopInfoMapper;
import com.zhiflow.mall.admin.mapper.ShoppingCartMapper;
import com.zhiflow.mall.admin.service.GoodsSkuService;
import com.zhiflow.mall.admin.service.GoodsSkuSpecValueService;
import com.zhiflow.mall.admin.service.GoodsSpecService;
import com.zhiflow.mall.admin.service.GoodsSpecValueService;
import com.zhiflow.mall.admin.service.GoodsSpuService;
import com.zhiflow.mall.admin.service.GoodsSpuSpecService;
import com.zhiflow.mall.admin.service.ShopInfoService;
import com.zhiflow.mall.admin.service.ShoppingCartService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.zhiflow.common.test.WriteEvictScanner.internal;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台每个会改变用户端购物车展示的写入口，都必须清购物车里受影响用户的整车缓存（{@code mall:shopping_cart#600}，
 * 经 {@link ShoppingCartCacheEviction}）：商品 / SKU / 规格类表（与商品详情同一批入口，经
 * {@link GoodsDetailCacheEviction#evictAfterCommit(Map)} 顺带清）、店铺表、后台自己的购物车写入口。
 *
 * <p>漏一个入口的后果是：后台改了价格 / 上下架 / 店铺状态，用户购物车里最长 10 分钟还是旧的，而且不会有任何报错。
 * 判据钉死到「以购物车缓存名调用 {@code AfterCommitCacheEviction#evictForTenant}」：把
 * {@code GoodsDetailCacheEviction} 里清购物车那一句拿掉，商品类的全部入口都会在这里报出来。
 * 用户端（mall-api）的写入口由 mall-api 的同名契约测试守。
 */
class ShoppingCartCacheEvictContractTest {

	private static final Set<String> WRITE_IMPLS = Stream.of(GoodsSpuServiceImpl.class, GoodsSkuServiceImpl.class,
					GoodsSpuSpecServiceImpl.class, GoodsSkuSpecValueServiceImpl.class, GoodsSpecServiceImpl.class,
					GoodsSpecValueServiceImpl.class, ShopInfoServiceImpl.class, ShoppingCartServiceImpl.class)
			.map(WriteEvictScanner::internal).collect(Collectors.toSet());

	private static final Set<String> WRITE_OWNERS = Stream.concat(WRITE_IMPLS.stream(), Stream.of(
					GoodsSpuMapper.class, GoodsSkuMapper.class, GoodsSpuSpecMapper.class, GoodsSkuSpecValueMapper.class,
					GoodsSpecMapper.class, GoodsSpecValueMapper.class, ShopInfoMapper.class, ShoppingCartMapper.class,
					GoodsSpuService.class, GoodsSkuService.class, GoodsSpuSpecService.class, GoodsSkuSpecValueService.class,
					GoodsSpecService.class, GoodsSpecValueService.class, ShopInfoService.class, ShoppingCartService.class)
			.map(WriteEvictScanner::internal))
			.collect(Collectors.toSet());

	/** 豁免必须写明理由。 */
	private static final Map<String, String> EXEMPT = Map.of(
			"GoodsSpecServiceImpl#save", "新建的规格还没有任何商品用到，不影响任何购物车",
			"GoodsSpecValueServiceImpl#save", "新建的规格值还没有任何 SKU 用到，不影响任何购物车");

	private static WriteEvictScanner scanner;

	@BeforeAll
	static void scan() {
		scanner = WriteEvictScanner.scan("com.zhiflow.mall.admin");
	}

	private static boolean evictsCart(WriteEvictScanner.MethodInfo method) {
		return method.invokes(internal(AfterCommitCacheEviction.class), "evictForTenant",
				CacheConstants.MALL_SHOPPING_CART_CACHE);
	}

	@Test
	@DisplayName("后台每个写商品 / SKU / 规格 / 店铺 / 购物车表的入口都清了购物车缓存")
	void everyWriteEntryEvictsCart() {
		Set<String> violations = scanner.uncovered(WRITE_OWNERS, WRITE_IMPLS,
				ShoppingCartCacheEvictContractTest::evictsCart, EXEMPT);

		assertTrue(violations.isEmpty(), "以下写入口没有清购物车缓存（走 ShoppingCartCacheEviction）：\n  "
				+ String.join("\n  ", violations));
	}

	@Test
	@DisplayName("对照组：判据换成「谁都没清」时必须报出已知入口——否则上面那条的通过不说明任何事")
	void detectorIsNotVacuous() {
		assertTrue(scanner.countWrites(WRITE_OWNERS, WRITE_IMPLS) >= 25, "扫描到的写调用太少，判据可能扫空了");

		Set<String> violations = scanner.uncovered(WRITE_OWNERS, WRITE_IMPLS, method -> false, Map.of());

		for (String known : Set.of("GoodsSkuController#updateById", "GoodsSpuController#update", "GoodsSpuServiceImpl#shelf",
				"GoodsImportRowWriterImpl#refreshPriceRange", "ShopInfoController#updateById",
				"ShoppingCartServiceImpl#save", "ShoppingCartServiceImpl#removeByIds")) {
			assertTrue(violations.stream().anyMatch(v -> v.startsWith(known + " ")),
					"对照组没报出 " + known + "，实际：\n  " + String.join("\n  ", violations));
		}
	}
}
