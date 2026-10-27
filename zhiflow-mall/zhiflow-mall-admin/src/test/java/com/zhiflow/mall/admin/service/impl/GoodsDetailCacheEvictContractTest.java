/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.mall.admin.mapper.GoodsSkuMapper;
import com.zhiflow.mall.admin.mapper.GoodsSkuSpecValueMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpecMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpecValueMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpuMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpuSpecMapper;
import com.zhiflow.mall.admin.service.GoodsSkuService;
import com.zhiflow.mall.admin.service.GoodsSkuSpecValueService;
import com.zhiflow.mall.admin.service.GoodsSpecService;
import com.zhiflow.mall.admin.service.GoodsSpecValueService;
import com.zhiflow.mall.admin.service.GoodsSpuService;
import com.zhiflow.mall.admin.service.GoodsSpuSpecService;
import com.zhiflow.common.test.WriteEvictScanner;
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
 * 后台写 goods_spu / goods_sku / 规格相关表的每个入口，都必须清用户端商品详情缓存
 * （{@code mall:goods_detail#600}、{@code mall:goods_stock#5}，经 {@link GoodsDetailCacheEviction}）。
 *
 * <p>漏一个入口的后果是：后台改了价格 / 上下架 / 规格，用户端最长 10 分钟还是旧的，而且不会有任何报错。
 * 以后有人新加写入口没走 {@link GoodsDetailCacheEviction}，这里会列出「哪个方法 → 哪个写调用」。
 *
 * <p>只有后台（mall-admin）在这里管。用户端下单扣库存、支付加销量、取消回补<b>有意不清</b>：
 * 库存销量缓存只有 5 秒，热门商品每 5 秒最多回源一次。
 */
class GoodsDetailCacheEvictContractTest {

	private static final Set<String> WRITE_IMPLS = Stream.of(GoodsSpuServiceImpl.class, GoodsSkuServiceImpl.class,
					GoodsSpuSpecServiceImpl.class, GoodsSkuSpecValueServiceImpl.class, GoodsSpecServiceImpl.class,
					GoodsSpecValueServiceImpl.class)
			.map(WriteEvictScanner::internal).collect(Collectors.toSet());

	private static final Set<String> WRITE_OWNERS = Stream.concat(WRITE_IMPLS.stream(), Stream.of(
					GoodsSpuMapper.class, GoodsSkuMapper.class, GoodsSpuSpecMapper.class, GoodsSkuSpecValueMapper.class,
					GoodsSpecMapper.class, GoodsSpecValueMapper.class,
					GoodsSpuService.class, GoodsSkuService.class, GoodsSpuSpecService.class, GoodsSkuSpecValueService.class,
					GoodsSpecService.class, GoodsSpecValueService.class)
			.map(WriteEvictScanner::internal))
			.collect(Collectors.toSet());

	/** 豁免必须写明理由。 */
	private static final Map<String, String> EXEMPT = Map.of(
			"GoodsSpecServiceImpl#save", "新建的规格还没有任何商品用到，不影响商品详情",
			"GoodsSpecValueServiceImpl#save", "新建的规格值还没有任何 SKU 用到，不影响商品详情");

	private static WriteEvictScanner scanner;

	@BeforeAll
	static void scan() {
		scanner = WriteEvictScanner.scan("com.zhiflow.mall.admin");
	}

	private static boolean evictsGoodsDetail(WriteEvictScanner.MethodInfo method) {
		return method.invokes(internal(GoodsDetailCacheEviction.class), "evictAfterCommit");
	}

	@Test
	@DisplayName("后台每个写商品 / SKU / 规格表的入口都清了商品详情缓存")
	void everyWriteEntryEvictsGoodsDetail() {
		Set<String> violations = scanner.uncovered(WRITE_OWNERS, WRITE_IMPLS,
				GoodsDetailCacheEvictContractTest::evictsGoodsDetail, EXEMPT);

		assertTrue(violations.isEmpty(), "以下写入口没有清商品详情缓存（走 GoodsDetailCacheEviction）：\n  "
				+ String.join("\n  ", violations));
	}

	@Test
	@DisplayName("对照组：判据换成「谁都没清」时必须报出已知入口——否则上面那条的通过不说明任何事")
	void detectorIsNotVacuous() {
		assertTrue(scanner.countWrites(WRITE_OWNERS, WRITE_IMPLS) >= 20, "扫描到的写调用太少，判据可能扫空了");

		Set<String> violations = scanner.uncovered(WRITE_OWNERS, WRITE_IMPLS, method -> false, Map.of());

		for (String known : Set.of("GoodsSkuController#updateById", "GoodsSpuController#update", "GoodsSpecValueController#updateById",
				"GoodsSpuSpecController#removeById", "GoodsSpuServiceImpl#shelf", "GoodsImportRowWriterImpl#refreshPriceRange")) {
			assertTrue(violations.stream().anyMatch(v -> v.startsWith(known + " ")),
					"对照组没报出 " + known + "，实际：\n  " + String.join("\n  ", violations));
		}
	}
}
