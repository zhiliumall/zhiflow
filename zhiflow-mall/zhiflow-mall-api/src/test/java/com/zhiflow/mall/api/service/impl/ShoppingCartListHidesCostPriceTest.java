/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import com.zhiflow.mall.api.mapper.ShoppingCartMapper;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.ShoppingCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /mallapi/shoppingcart/page 不能带出 SKU 成本价；/count 是一条 COUNT，不再把整车查出来数。
 * （两者数的是同一批行，见 ShoppingCartCountPgTest。）
 */
class ShoppingCartListHidesCostPriceTest {

	@Test
	@DisplayName("列表返回前成本价置空（查询本身也不取 cost_price）")
	void listHidesCostPrice() throws IOException {
		ShoppingCartMapper mapper = mock(ShoppingCartMapper.class);
		ShoppingCart row = new ShoppingCart();
		row.setGoodsSpu(new GoodsSpu());
		GoodsSku sku = new GoodsSku();
		sku.setCostPrice(new BigDecimal("3.00"));
		sku.setSalesPrice(new BigDecimal("10.00"));
		row.setGoodsSku(sku);
		when(mapper.selectList2Optimized(any())).thenReturn(List.of(row));

		List<ShoppingCart> rows = service(mapper).listCart(new ShoppingCart());

		assertNull(rows.get(0).getGoodsSku().getCostPrice());
		assertEquals(new BigDecimal("10.00"), rows.get(0).getGoodsSku().getSalesPrice());
		String xml;
		try (InputStream in = getClass().getResourceAsStream("/mapper/mall-api/ShoppingCartMapper.xml")) {
			xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		String sql = xml.substring(xml.indexOf("<sql id=\"shoppingCartOptimizedSql\">"), xml.indexOf("</sql>"));
		assertFalse(sql.contains("cost_price"));
		assertFalse(sql.contains("spu.description"));
		assertFalse(sql.contains("spu.parameter_json"));
	}

	@Test
	@DisplayName("件数走一条 COUNT，不再调列表查询")
	void countUsesSqlCount() {
		ShoppingCartMapper mapper = mock(ShoppingCartMapper.class);
		when(mapper.countByCondition(any())).thenReturn(4);

		assertEquals(4, service(mapper).countByCondition(new ShoppingCart()));
		verify(mapper, never()).selectList2Optimized(any());
	}

	private static ShoppingCartServiceImpl service(ShoppingCartMapper mapper) {
		ShoppingCartServiceImpl service = new ShoppingCartServiceImpl(null, null, null);
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		return service;
	}
}
