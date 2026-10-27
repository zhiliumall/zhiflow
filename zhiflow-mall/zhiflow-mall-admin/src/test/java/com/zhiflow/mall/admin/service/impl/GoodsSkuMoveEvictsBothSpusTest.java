/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.zhiflow.mall.admin.mapper.GoodsSkuMapper;
import com.zhiflow.mall.common.entity.GoodsSku;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台改 SKU 时请求可能把它挪到别的商品：改前、改后各查一次所属商品，两个商品的详情缓存都要清。
 * 只在写库后查的话，原商品的缓存清不到，用户端最长 10 分钟还能看到这个 SKU。
 */
class GoodsSkuMoveEvictsBothSpusTest {

	@Test
	@DisplayName("updateById：SKU 从商品 A 挪到商品 B，A、B 都清")
	void updateByIdEvictsOldAndNewSpu() {
		GoodsDetailCacheEviction eviction = mock(GoodsDetailCacheEviction.class);
		GoodsSkuServiceImpl service = service(eviction);
		when(eviction.ofSkus(List.of("201"))).thenReturn(spus("A")).thenReturn(spus("B"));
		GoodsSku sku = new GoodsSku();
		sku.setId("201");
		sku.setSpuId("B");

		service.updateById(sku);

		assertEquals(Map.of("A", "1", "B", "1"), evicted(eviction));
	}

	@Test
	@DisplayName("update(wrapper)：同样改前改后各取一次")
	void updateWrapperEvictsOldAndNewSpu() {
		GoodsDetailCacheEviction eviction = mock(GoodsDetailCacheEviction.class);
		GoodsSkuServiceImpl service = service(eviction);
		when(eviction.ofSkus(List.of("201"))).thenReturn(spus("A")).thenReturn(spus("B"));
		GoodsSku sku = new GoodsSku();
		sku.setId("201");

		service.update(new UpdateWrapper<>(sku));

		assertEquals(Map.of("A", "1", "B", "1"), evicted(eviction));
	}

	private static GoodsSkuServiceImpl service(GoodsDetailCacheEviction eviction) {
		GoodsSkuMapper mapper = mock(GoodsSkuMapper.class);
		when(mapper.updateById(any(GoodsSku.class))).thenReturn(1);
		when(mapper.update(any(), any())).thenReturn(1);
		GoodsSkuServiceImpl service = new GoodsSkuServiceImpl(eviction);
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		return service;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> evicted(GoodsDetailCacheEviction eviction) {
		ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
		verify(eviction).evictAfterCommit(captor.capture());
		return captor.getValue();
	}

	private static Map<String, String> spus(String spuId) {
		Map<String, String> spus = new LinkedHashMap<>();
		spus.put(spuId, "1");
		return spus;
	}
}
