/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import com.zhiflow.mall.api.cache.GoodsDetailCache;
import com.zhiflow.mall.api.cache.GoodsDetailSnapshot;
import com.zhiflow.mall.api.cache.GoodsStockSnapshot;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSpu;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C 端商详（/mallapi/goodsspu/{id}、/mallapi/goodsspu/detail/init/{id}）匿名可读，不能带出 SKU 成本价。
 *
 * <p>现在的做法是根本不查：详情 SKU 的查询不取 cost_price，快照里也没有这个字段（见 GoodsDetailSnapshotTest）。
 */
class GoodsSpuDetailHidesCostPriceTest {

	@Test
	void detailDoesNotExposeSkuCostPrice() {
		GoodsSku sku = new GoodsSku();
		sku.setId("1");
		sku.setSalesPrice(new BigDecimal("79.00"));
		sku.setCostPrice(new BigDecimal("20.00"));
		GoodsSpu spu = new GoodsSpu();
		spu.setId("100");
		GoodsDetailCache cache = mock(GoodsDetailCache.class);
		when(cache.getDetail("100")).thenReturn(GoodsDetailSnapshot.of(spu, List.of(sku), Map.of(), List.of()));
		when(cache.getStock("100")).thenReturn(new GoodsStockSnapshot());

		GoodsSpuServiceImpl service = new GoodsSpuServiceImpl(null, null, cache, null, null);
		GoodsSpu out = service.getById2Optimized("100");

		assertNull(out.getSkus().get(0).getCostPrice(), "商详不应返回成本价");
		assertEquals(new BigDecimal("79.00"), out.getSkus().get(0).getSalesPrice());
	}

	@Test
	void detailSkuQueryDoesNotSelectCostPrice() throws IOException {
		String xml;
		try (InputStream in = getClass().getResourceAsStream("/mapper/mall-api/GoodsSkuMapper.xml")) {
			xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		Matcher m = Pattern.compile("<select id=\"listDetailSkuBySpuId\".*?</select>", Pattern.DOTALL).matcher(xml);
		assertTrue(m.find(), "找不到 listDetailSkuBySpuId");
		assertFalse(m.group().contains("cost_price"), "商详 SKU 查询不能取成本价");
		assertFalse(m.group().contains("goodsSkuSql"), "不能整段复用带成本价的列清单");
	}
}
