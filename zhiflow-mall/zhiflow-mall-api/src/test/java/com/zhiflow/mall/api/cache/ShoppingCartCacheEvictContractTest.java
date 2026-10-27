/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import com.zhiflow.common.test.WriteEvictScanner;
import com.zhiflow.mall.api.mapper.ShoppingCartMapper;
import com.zhiflow.mall.api.service.ShoppingCartService;
import com.zhiflow.mall.api.service.impl.ShoppingCartServiceImpl;
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
 * 用户端（mall-api）每个写 shopping_cart 的入口，都必须清该用户的整车缓存（{@code mall:shopping_cart#600}，
 * 经 {@link ShoppingCartCache#evictAfterCommit}）。
 *
 * <p>漏一个入口的后果是：加购 / 删除 / 下单之后，购物车页最长 10 分钟还是旧的，而且不会有任何报错。
 * 以后有人新加写入口（例如账号合并调了 {@code ShoppingCartMapper#updateUserId}）没清，这里会列出「哪个方法 → 哪个写调用」。
 * 后台的写入口（商品 / SKU / 规格 / 店铺 / 后台购物车）由 mall-admin 的同名契约测试守。
 */
class ShoppingCartCacheEvictContractTest {

	private static final Set<String> WRITE_IMPLS = Set.of(internal(ShoppingCartServiceImpl.class));

	private static final Set<String> WRITE_OWNERS = Stream.concat(WRITE_IMPLS.stream(),
					Stream.of(ShoppingCartMapper.class, ShoppingCartService.class).map(WriteEvictScanner::internal))
			.collect(Collectors.toSet());

	private static WriteEvictScanner scanner;

	@BeforeAll
	static void scan() {
		scanner = WriteEvictScanner.scan("com.zhiflow.mall.api");
	}

	private static boolean evictsCart(WriteEvictScanner.MethodInfo method) {
		return method.invokes(internal(ShoppingCartCache.class), "evictAfterCommit");
	}

	@Test
	@DisplayName("用户端每个写购物车的入口都清了该用户的购物车缓存")
	void everyWriteEntryEvictsCart() {
		Set<String> violations = scanner.uncovered(WRITE_OWNERS, WRITE_IMPLS,
				ShoppingCartCacheEvictContractTest::evictsCart, Map.of());

		assertTrue(violations.isEmpty(), "以下写入口没有清购物车缓存（走 ShoppingCartCache#evictAfterCommit）：\n  "
				+ String.join("\n  ", violations));
	}

	@Test
	@DisplayName("对照组：判据换成「谁都没清」时必须报出已知入口——否则上面那条的通过不说明任何事")
	void detectorIsNotVacuous() {
		assertTrue(scanner.countWrites(WRITE_OWNERS, WRITE_IMPLS) >= 8, "扫描到的写调用太少，判据可能扫空了");

		Set<String> violations = scanner.uncovered(WRITE_OWNERS, WRITE_IMPLS, method -> false, Map.of());

		for (String known : Set.of("ShoppingCartController#save", "ShoppingCartController#edit", "ShoppingCartController#del",
				"OrderInfoServiceImpl#orderAddOne")) {
			assertTrue(violations.stream().anyMatch(v -> v.startsWith(known)),
					"对照组没报出 " + known + "，实际：\n  " + String.join("\n  ", violations));
		}
	}
}
