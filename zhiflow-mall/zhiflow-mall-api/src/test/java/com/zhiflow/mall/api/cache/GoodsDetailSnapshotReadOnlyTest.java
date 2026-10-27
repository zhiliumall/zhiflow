/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import com.zhiflow.common.data.cache.RedisTemplateConfig;
import com.zhiflow.mall.api.service.GoodsBrandService;
import com.zhiflow.mall.api.service.ShopInfoService;
import com.zhiflow.mall.api.service.impl.GoodsSpuServiceImpl;
import com.zhiflow.mall.common.dto.GoodsDetailAggregateDTO;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.vo.GoodsSpecVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 商品详情快照开了进程内 L1 之后，命中时多个请求拿到<b>同一个</b>快照实例（RedisL1Settings 白名单 2026-10-23 审计）。
 * 守护：读方（GoodsSpuServiceImpl 三处）拿到的响应对象随便改，快照本身和下一个请求的结果都不受影响。
 * 以后新增读方如果直接改快照，这里要么编译不过（没有转换），要么断言变红。
 */
class GoodsDetailSnapshotReadOnlyTest {

	private static GoodsDetailSnapshot snapshot() {
		return GoodsDetailSnapshot.of(GoodsDetailSnapshotTest.spu(), GoodsDetailSnapshotTest.skus(),
				GoodsDetailSnapshotTest.specsBySku(), GoodsDetailSnapshotTest.specTree());
	}

	@Test
	@DisplayName("经商详聚合 / 精简详情 / 商品参数读同一个快照实例，改坏返回值不污染快照")
	void readersNeverMutateSharedSnapshot() {
		GoodsDetailSnapshot shared = snapshot();
		GoodsDetailCache cache = mock(GoodsDetailCache.class);
		when(cache.getDetail("100")).thenReturn(shared);
		when(cache.getStock("100")).thenReturn(GoodsDetailSnapshotTest.stock());
		GoodsSpuServiceImpl service = new GoodsSpuServiceImpl(null, mock(GoodsBrandService.class), cache,
				mock(ShopInfoService.class), null);

		GoodsDetailAggregateDTO first = service.getGoodsDetailAggregate("100");
		vandalize(first.getGoodsSpu());
		first.getSpecTree().forEach(spec -> {
			spec.setValue("改");
			spec.getLeaf().forEach(leaf -> leaf.setValue("改"));
			spec.getLeaf().clear();
		});
		first.getSpecTree().clear();
		vandalize(service.getById2Optimized("100"));
		service.listParameters("100");

		assertEquals(snapshot(), shared, "快照被读方改了");
		GoodsDetailAggregateDTO second = service.getGoodsDetailAggregate("100");
		assertEquals("测试商品", second.getGoodsSpu().getName());
		assertEquals(3, second.getGoodsSpu().getSkus().size());
		assertEquals("红", second.getGoodsSpu().getSkus().get(0).getSpecs().get(0).getSpecValueName());
		assertEquals("https://img/a.png", second.getGoodsSpu().getPicUrls()[0]);
		assertEquals(2, second.getSpecTree().size());
		assertEquals("颜色", second.getSpecTree().get(0).getValue());
	}

	private static void vandalize(GoodsSpu spu) {
		spu.setName("改");
		spu.setDescription("改");
		spu.getPicUrls()[0] = "改";
		spu.setPriceDown(BigDecimal.ONE);
		for (GoodsSku sku : spu.getSkus()) {
			sku.setSalesPrice(BigDecimal.ONE);
			for (GoodsSkuSpecValue value : sku.getSpecs()) {
				value.setSpecValueName("改");
			}
			sku.getSpecs().clear();
		}
		spu.getSkus().clear();
	}

	@Test
	@DisplayName("toSpecTree 每次新建：两次结果不是同一批对象")
	void specTreeIsFreshEachTime() {
		GoodsDetailSnapshot shared = snapshot();
		List<GoodsSpecVO> a = shared.toSpecTree();
		List<GoodsSpecVO> b = shared.toSpecTree();
		a.get(0).setValue("改");
		assertEquals("颜色", b.get(0).getValue());
	}

	@Test
	@DisplayName("L1 权重：按近似 KB 计，随描述长度增长；不会被 Jackson 当字段写进 Redis")
	void l1WeightIsKbAndNotSerialized() {
		GoodsDetailSnapshot small = snapshot();
		GoodsSpu big = GoodsDetailSnapshotTest.spu();
		big.setDescription("详".repeat(20_000));
		GoodsDetailSnapshot large = GoodsDetailSnapshot.of(big, GoodsDetailSnapshotTest.skus(),
				GoodsDetailSnapshotTest.specsBySku(), GoodsDetailSnapshotTest.specTree());

		assertTrue(small.l1WeightKb() >= 1 && small.l1WeightKb() <= 8, "小快照约几 KB：" + small.l1WeightKb());
		// 2 万个汉字 ≈ 40 KB（UTF-16）
		assertTrue(large.l1WeightKb() >= 40, "大描述必须按字节计权：" + large.l1WeightKb());

		String json = new String(RedisTemplateConfig.createValkeyOptimizedJsonSerializer().serialize(small),
				StandardCharsets.UTF_8);
		assertFalse(json.contains("l1Weight"), json);
		assertEquals(small, RedisTemplateConfig.createValkeyOptimizedJsonSerializer()
			.deserialize(json.getBytes(StandardCharsets.UTF_8)));
	}

}
