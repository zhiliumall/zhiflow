/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.mall.admin.mapper.GoodsBrandMapper;
import com.zhiflow.mall.admin.service.GoodsBrandService;
import com.zhiflow.common.test.WriteEvictScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.zhiflow.common.test.WriteEvictScanner.internal;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台写 goods_brand 的每个入口都必须提交后清用户端品牌缓存（{@code mall:goods_brand#1800}），
 * 否则品牌改名、换图后商品详情里最长 30 分钟还是旧的。判据见 {@link WriteEvictScanner}。
 */
class GoodsBrandCacheEvictContractTest {

	private static final Set<String> WRITE_IMPLS = Set.of(internal(GoodsBrandServiceImpl.class));

	private static final Set<String> WRITE_OWNERS = Set.of(internal(GoodsBrandServiceImpl.class),
			internal(GoodsBrandService.class), internal(GoodsBrandMapper.class));

	private static final Map<String, String> EXEMPT = Map.of(
			"GoodsBrandServiceImpl#save", "新品牌在用户端缓存里还没有键（查不到的品牌不缓存）");

	@Test
	@DisplayName("后台每个写品牌的入口都清了品牌缓存；对照组（判据换成谁都没清）必须报出改、删两个入口")
	void everyWriteEntryEvictsBrand() {
		WriteEvictScanner scanner = WriteEvictScanner.scan("com.zhiflow.mall.admin");

		Set<String> violations = scanner.uncovered(WRITE_OWNERS, WRITE_IMPLS, method -> method.invokes(
				internal(AfterCommitCacheEviction.class), "evictForTenant", CacheConstants.MALL_GOODS_BRAND_CACHE), EXEMPT);
		assertTrue(violations.isEmpty(), "以下写入口没有清品牌缓存：\n  " + String.join("\n  ", violations));

		Set<String> control = scanner.uncovered(WRITE_OWNERS, WRITE_IMPLS, method -> false, Map.of());
		for (String known : Set.of("GoodsBrandController#updateById", "GoodsBrandController#removeById")) {
			assertTrue(control.stream().anyMatch(v -> v.startsWith(known + " ")), "对照组没报出 " + known + "：" + control);
		}
	}
}
