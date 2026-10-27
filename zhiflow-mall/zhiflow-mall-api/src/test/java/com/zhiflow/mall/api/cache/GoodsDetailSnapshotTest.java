/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import com.zhiflow.common.data.cache.RedisTemplateConfig;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.vo.GoodsSpecLeafVO;
import com.zhiflow.mall.common.vo.GoodsSpecVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 商品详情两份缓存值的护栏：
 * <ul>
 * <li>用生产的缓存序列化器写进去、读出来，逐字段相等（含 BigDecimal 的小数位、LocalDateTime 的纳秒）；</li>
 * <li>快照叠加库存后转回的响应对象，与改动前直接查库得到的对象相等（成本价除外，它本来就要置空）；</li>
 * <li>快照里没有成本价这个字段。</li>
 * </ul>
 * 往返不过时，{@code DefensiveRedisCache} 会吞掉异常、按未命中处理——功能照常，缓存恒为 0 命中，不会有任何报错。
 */
class GoodsDetailSnapshotTest {

	private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 22, 21, 20, 58, 123_456_000);

	private static final LocalDateTime T2 = LocalDateTime.of(2026, 10, 22, 21, 30, 1, 987_654_000);

	private final GenericJacksonJsonRedisSerializer serializer = RedisTemplateConfig.createValkeyOptimizedJsonSerializer();

	@Test
	@DisplayName("详情快照经生产序列化器往返后逐字段相等")
	void detailSnapshotRoundTrips() {
		GoodsDetailSnapshot snapshot = GoodsDetailSnapshot.of(spu(), skus(), specsBySku(), specTree());

		Object restored = serializer.deserialize(serializer.serialize(snapshot));

		assertInstanceOf(GoodsDetailSnapshot.class, restored);
		assertEquals(snapshot, restored);
	}

	@Test
	@DisplayName("库存快照经生产序列化器往返后逐字段相等")
	void stockSnapshotRoundTrips() {
		GoodsStockSnapshot stock = stock();

		Object restored = serializer.deserialize(serializer.serialize(stock));

		assertInstanceOf(GoodsStockSnapshot.class, restored);
		assertEquals(stock, restored);
	}

	@Test
	@DisplayName("快照（经缓存往返）+ 库存 转回的商品，与改动前查库得到的商品相等，只是成本价为空")
	void restoredSpuEqualsWhatTheDatabaseReturned() {
		GoodsDetailSnapshot snapshot = (GoodsDetailSnapshot) serializer.deserialize(
				serializer.serialize(GoodsDetailSnapshot.of(spu(), skus(), specsBySku(), specTree())));
		GoodsStockSnapshot stock = (GoodsStockSnapshot) serializer.deserialize(serializer.serialize(stock()));

		GoodsSpu expected = spu();
		expected.setSaleNum(stock.getSaleNum());
		List<GoodsSku> expectedSkus = skus();
		for (GoodsSku sku : expectedSkus) {
			GoodsStockSnapshot.SkuStock s = stock.getSkus().stream().filter(x -> x.getSkuId().equals(sku.getId())).findFirst().orElseThrow();
			sku.setStock(s.getStock());
			sku.setVersion(s.getVersion());
			sku.setSpecs(specsBySku().getOrDefault(sku.getId(), new ArrayList<>()));
		}
		expected.setSkus(expectedSkus);

		assertEquals(expected, snapshot.toGoodsSpu(stock));
		assertEquals(specTree(), snapshot.toSpecTree());
	}

	@Test
	@DisplayName("库存缓存里缺某个 SKU（后台改了 SKU 但清缓存失败）时按 0 展示，不抛")
	void missingStockShowsZero() {
		GoodsDetailSnapshot snapshot = GoodsDetailSnapshot.of(spu(), skus(), specsBySku(), specTree());

		GoodsSpu out = snapshot.toGoodsSpu(null);

		assertNull(out.getSaleNum());
		out.getSkus().forEach(sku -> {
			assertEquals(0, sku.getStock());
			assertNull(sku.getVersion());
		});
	}

	@Test
	@DisplayName("快照与 SKU 快照都没有成本价、库存字段：成本价不会进缓存，也就不会被带出去")
	void snapshotHasNoCostPriceOrStock() {
		for (Class<?> type : List.of(GoodsDetailSnapshot.class, GoodsDetailSnapshot.Sku.class)) {
			List<String> fields = Arrays.stream(type.getDeclaredFields()).map(Field::getName).toList();
			assertFalse(fields.contains("costPrice"), type.getSimpleName());
			assertFalse(fields.contains("stock"), type.getSimpleName());
			assertFalse(fields.contains("saleNum"), type.getSimpleName());
		}
		GoodsSpu out = GoodsDetailSnapshot.of(spu(), skus(), specsBySku(), specTree()).toGoodsSpu(stock());
		out.getSkus().forEach(sku -> assertNull(sku.getCostPrice()));
	}

	/** 与 GoodsSpuMapper#selectById2 映射出的字段一致（sale_num 不进快照，另由库存缓存给）。 */
	static GoodsSpu spu() {
		GoodsSpu spu = new GoodsSpu();
		spu.setId("100");
		spu.setTenantId("1");
		spu.setShopId("1");
		spu.setSpuCode("SPU-T1");
		spu.setName("测试商品");
		spu.setSellPoint("卖点");
		spu.setDescription("<p>详情</p>");
		spu.setCategoryFirst("11");
		spu.setCategorySecond("12");
		spu.setVideoUrl(null);
		spu.setPicUrls(new String[] { "https://img/a.png", "https://img/b.png" });
		spu.setShelf("1");
		spu.setSort(1);
		spu.setPriceDown(new BigDecimal("20.00"));
		spu.setPriceUp(new BigDecimal("30.00"));
		spu.setCreateTime(T1);
		spu.setUpdateTime(T2);
		spu.setSpecType("1");
		spu.setDelFlag("0");
		spu.setFreightType("0");
		spu.setFreightAmount(null);
		spu.setVerifyStatus("1");
		spu.setBrandId("7");
		spu.setLabel(new String[0]);
		spu.setParameterJson("{\"data\":[]}");
		spu.setViewNum(0);
		return spu;
	}

	/** 与 GoodsSkuMapper#listDetailSkuBySpuId 映射出的字段一致（不含成本价、库存、版本号）。 */
	static List<GoodsSku> skus() {
		List<GoodsSku> skus = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			GoodsSku sku = new GoodsSku();
			sku.setId("20" + i);
			sku.setCreateTime(T1);
			sku.setUpdateTime(T2);
			sku.setTenantId("1");
			sku.setShopId("1");
			sku.setSkuCode("SKU-" + i);
			sku.setSpuId("100");
			sku.setPicUrl("https://img/sku.png");
			sku.setSalesPrice(new BigDecimal("2" + i + ".00"));
			sku.setMarketPrice(new BigDecimal("3" + i + ".50"));
			sku.setWeight(i == 1 ? null : new BigDecimal("0.125"));
			sku.setVolume(null);
			sku.setDelFlag("0");
			sku.setEnable("1");
			skus.add(sku);
		}
		return skus;
	}

	static Map<String, List<GoodsSkuSpecValue>> specsBySku() {
		return Map.of(
				"201", new ArrayList<>(List.of(specValue("301", "201", "401", "红", 0), specValue("302", "201", "402", "小", 1))),
				"202", new ArrayList<>(List.of(specValue("303", "202", "401", "红", 2), specValue("304", "202", "402", "大", 3))));
	}

	private static GoodsSkuSpecValue specValue(String id, String skuId, String specId, String name, int sort) {
		GoodsSkuSpecValue value = new GoodsSkuSpecValue();
		value.setId(id);
		value.setTenantId("1");
		value.setSpuId("100");
		value.setSkuId(skuId);
		value.setSpecValueId("5" + id);
		value.setCreateTime(T1);
		value.setUpdateTime(T2);
		value.setSort(sort);
		value.setSpecId(specId);
		value.setSpecValueName(name);
		return value;
	}

	static List<GoodsSpecVO> specTree() {
		GoodsSpecVO color = new GoodsSpecVO();
		color.setId("401");
		color.setSpuId("100");
		color.setValue("颜色");
		color.setLeaf(new ArrayList<>(List.of(leaf("5301", "红"))));
		GoodsSpecVO size = new GoodsSpecVO();
		size.setId("402");
		size.setSpuId("100");
		size.setValue("尺寸");
		size.setLeaf(new ArrayList<>(List.of(leaf("5302", "小"), leaf("5304", "大"))));
		return new ArrayList<>(List.of(color, size));
	}

	private static GoodsSpecLeafVO leaf(String id, String value) {
		GoodsSpecLeafVO leaf = new GoodsSpecLeafVO();
		leaf.setId(id);
		leaf.setValue(value);
		return leaf;
	}

	static GoodsStockSnapshot stock() {
		GoodsStockSnapshot stock = new GoodsStockSnapshot();
		stock.setSpuId("100");
		stock.setSaleNum(12);
		List<GoodsStockSnapshot.SkuStock> skus = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			GoodsStockSnapshot.SkuStock s = new GoodsStockSnapshot.SkuStock();
			s.setSkuId("20" + i);
			s.setStock(100 - i);
			s.setVersion(i);
			skus.add(s);
		}
		stock.setSkus(skus);
		return stock;
	}
}
