/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.mall.api.mapper.OrderInfoMapper;
import com.zhiflow.mall.api.mapper.OrderItemMapper;
import com.zhiflow.mall.api.mapper.OrderRecordMapper;
import com.zhiflow.mall.api.service.ShopInfoService;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.ShopInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单列表、订单详情里的店铺走 {@code mall:shop_info} 缓存（{@link ShopInfoService#getById}），不再单独查 shop_info：
 * 列表按去重后的 shopId 逐个取；详情的 resultMap 不再嵌套查店铺，改由代码回填。
 */
@ExtendWith(MockitoExtension.class)
class OrderShopInfoFromCacheTest {

	@Mock
	private OrderInfoMapper orderInfoMapper;

	@Mock
	private OrderItemMapper orderItemMapper;

	@Mock
	private OrderRecordMapper orderRecordMapper;

	@Mock
	private ShopInfoService shopInfoService;

	@InjectMocks
	private OrderInfoServiceImpl service;

	@Test
	@DisplayName("订单列表：同一店铺只取一次，走 getById（带缓存），不再 listByIds")
	void pageFillsShopFromCachePerDistinctShop() throws Exception {
		injectBaseMapper();
		ShopInfo shop = shop("1");
		when(shopInfoService.getById("1")).thenReturn(shop);
		Page<OrderInfo> page = new Page<>(1, 10);
		page.setRecords(List.of(paidOrder("a", "1"), paidOrder("b", "1"), paidOrder("c", null)));
		when(orderInfoMapper.selectPage2(any(IPage.class), any(OrderInfo.class))).thenReturn(page);

		IPage<OrderInfo> result = service.page2(page, new OrderInfo());

		verify(shopInfoService, times(1)).getById("1");
		verify(shopInfoService, never()).listByIds(anyCollection());
		assertSame(shop, result.getRecords().get(0).getShopInfo());
		assertSame(shop, result.getRecords().get(1).getShopInfo());
		assertNull(result.getRecords().get(2).getShopInfo());
	}

	@Test
	@DisplayName("订单详情：店铺由代码回填（走缓存）")
	void detailFillsShopFromCache() throws Exception {
		injectBaseMapper();
		ShopInfo shop = shop("1");
		when(shopInfoService.getById("1")).thenReturn(shop);
		when(orderInfoMapper.selectById2("a")).thenReturn(paidOrder("a", "1"));
		when(orderRecordMapper.selectList(any())).thenReturn(List.of());

		assertSame(shop, service.getById2("a").getShopInfo());
	}

	@Test
	@DisplayName("订单详情的 resultMap 不再嵌套查 shop_info")
	void detailResultMapNoLongerNestsShopQuery() throws IOException {
		String xml;
		try (InputStream in = getClass().getResourceAsStream("/mapper/mall-api/OrderInfoMapper.xml")) {
			xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		assertFalse(xml.contains("ShopInfoMapper.selectById"));
	}

	private static OrderInfo paidOrder(String id, String shopId) {
		OrderInfo order = new OrderInfo();
		order.setId(id);
		order.setShopId(shopId);
		order.setIsPay("1");
		order.setStatus("1");
		return order;
	}

	private static ShopInfo shop(String id) {
		ShopInfo shop = new ShopInfo();
		shop.setId(id);
		shop.setName("店");
		return shop;
	}

	private void injectBaseMapper() throws Exception {
		for (Class<?> c = service.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField("baseMapper");
				f.setAccessible(true);
				f.set(service, orderInfoMapper);
				return;
			}
			catch (NoSuchFieldException ignored) {
				// 继续往上找
			}
		}
		throw new NoSuchFieldException("baseMapper");
	}
}
