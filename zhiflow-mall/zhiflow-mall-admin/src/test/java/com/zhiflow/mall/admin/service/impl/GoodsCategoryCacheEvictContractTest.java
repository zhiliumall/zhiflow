/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.mall.admin.mapper.GoodsCategoryMapper;
import com.zhiflow.mall.admin.service.GoodsCategoryService;
import com.zhiflow.common.test.WriteEvictScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.zhiflow.common.test.WriteEvictScanner.internal;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台写 goods_category 的每个入口都必须提交后清用户端分类树缓存（{@code mall:goods_category#1800::tree}），
 * 否则分类增删改后 H5 分类页最长 30 分钟还是旧的。判据见 {@link WriteEvictScanner}。
 */
class GoodsCategoryCacheEvictContractTest {

	private static final Set<String> WRITE_IMPLS = Set.of(internal(GoodsCategoryServiceImpl.class));

	private static final Set<String> WRITE_OWNERS = Set.of(internal(GoodsCategoryServiceImpl.class),
			internal(GoodsCategoryService.class), internal(GoodsCategoryMapper.class));

	@Test
	@DisplayName("后台每个写分类的入口都清了分类树缓存；对照组必须报出增、改、删三个入口")
	void everyWriteEntryEvictsCategoryTree() {
		WriteEvictScanner scanner = WriteEvictScanner.scan("com.zhiflow.mall.admin");

		Set<String> violations = scanner.uncovered(WRITE_OWNERS, WRITE_IMPLS, method -> method.invokes(
				internal(AfterCommitCacheEviction.class), "evictForTenant", CacheConstants.MALL_GOODSCATEGORY_CACHE), Map.of());
		assertTrue(violations.isEmpty(), "以下写入口没有清分类树缓存：\n  " + String.join("\n  ", violations));

		Set<String> control = scanner.uncovered(WRITE_OWNERS, WRITE_IMPLS, method -> false, Map.of());
		for (String known : Set.of("GoodsCategoryController#save", "GoodsCategoryController#updateById",
				"GoodsCategoryController#removeById")) {
			assertTrue(control.stream().anyMatch(v -> v.startsWith(known + " ")), "对照组没报出 " + known + "：" + control);
		}
	}
}
