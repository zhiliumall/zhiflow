/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import com.zhiflow.common.data.cache.RedisTemplateConfig;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.ShopInfo;
import com.zhiflow.mall.common.entity.ShoppingCart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 购物车缓存值的护栏：
 * <ul>
 * <li>四个实体的<b>每一个</b>标量字段都填上不同的值，经生产的缓存序列化器往返、还原后与原对象相等——
 *     实体以后加了字段而快照没加，这里会红（字段是反射取的，不是手写清单）；</li>
 * <li>null、空数组、缺商品 / 规格 / 店铺的行原样往返；</li>
 * <li>每次还原出来的都是新对象，调用方改了不影响缓存里的值。</li>
 * </ul>
 * 往返不过时，{@code DefensiveRedisCache} 会吞掉异常按未命中处理——功能照常，缓存恒为 0 命中，不报错。
 */
class ShoppingCartSnapshotTest {

	private static final String COST_PRICE = "GoodsSku.costPrice";

	/**
	 * 快照有意不存的字段：成本价（用户端不能看，列表返回前置空），以及列表 SQL 不可能填的嵌套对象。
	 * 往这里加字段等于宣布「这个字段在购物车列表里恒为 null」，要先确认查询确实不填它。
	 */
	private static final Set<String> NOT_IN_SNAPSHOT = Set.of(
			COST_PRICE,
			"GoodsSku.specs", "GoodsSku.goodsSpu",
			"GoodsSpu.skus", "GoodsSpu.spuSpec", "GoodsSpu.goodsBrand",
			"ShoppingCart.specs");

	private final GenericJacksonJsonRedisSerializer serializer = RedisTemplateConfig.createValkeyOptimizedJsonSerializer();

	private int seq;

	@Test
	@DisplayName("每个标量字段都填满：经生产序列化器往返、还原后与原对象相等（成本价恒为空）")
	void everyFieldRoundTrips() throws Exception {
		ShoppingCart cart = filled(ShoppingCart.class);
		cart.setGoodsSpu(filled(GoodsSpu.class));
		cart.setGoodsSku(filled(GoodsSku.class));
		cart.setShopInfo(filled(ShopInfo.class));
		assertNotNull(cart.getGoodsSku().getCostPrice(), "前提：成本价确实填了");

		List<ShoppingCart> restored = roundTrip(List.of(cart)).toCarts();

		cart.getGoodsSku().setCostPrice(null);
		assertEquals(List.of(cart), restored);
	}

	@Test
	@DisplayName("null、空数组、缺商品 / 规格 / 店铺的行、空购物车原样往返")
	void nullsAndEmptiesRoundTrip() {
		ShoppingCart bare = new ShoppingCart();
		bare.setId("c-bare");
		GoodsSpu spu = new GoodsSpu();
		spu.setId("p1");
		spu.setPicUrls(new String[0]);
		spu.setLabel(null);
		spu.setCategoryId(new ArrayList<>());
		bare.setGoodsSpu(spu);
		ShoppingCart allNull = new ShoppingCart();

		assertEquals(List.of(bare, allNull), roundTrip(List.of(bare, allNull)).toCarts());
		assertEquals(List.of(), roundTrip(List.of()).toCarts());
	}

	@Test
	@DisplayName("每次还原都是新对象：调用方改了返回值，再还原一次不受影响")
	void toCartsReturnsFreshObjects() throws Exception {
		ShoppingCart cart = filled(ShoppingCart.class);
		cart.setGoodsSpu(filled(GoodsSpu.class));
		cart.setGoodsSku(filled(GoodsSku.class));
		ShoppingCartSnapshot snapshot = ShoppingCartSnapshot.of(List.of(cart));

		ShoppingCart first = snapshot.toCarts().get(0);
		first.setQuantity(-1);
		first.getGoodsSpu().getPicUrls()[0] = "改坏了";
		first.getGoodsSpu().getCategoryId().add("改坏了");
		first.getGoodsSku().setSalesPrice(BigDecimal.ZERO);
		ShoppingCart second = snapshot.toCarts().get(0);

		assertNotSame(first, second);
		assertEquals(cart.getQuantity(), second.getQuantity());
		assertEquals(cart.getGoodsSpu().getPicUrls()[0], second.getGoodsSpu().getPicUrls()[0]);
		assertEquals(cart.getGoodsSpu().getCategoryId(), second.getGoodsSpu().getCategoryId());
		assertEquals(cart.getGoodsSku().getSalesPrice(), second.getGoodsSku().getSalesPrice());
	}

	@Test
	@DisplayName("快照里没有成本价字段：成本价不会进缓存")
	void snapshotHasNoCostPrice() {
		for (Field field : ShoppingCartSnapshot.Sku.class.getDeclaredFields()) {
			if ("costPrice".equals(field.getName())) {
				fail("购物车快照不能存成本价");
			}
		}
		ShoppingCart cart = new ShoppingCart();
		GoodsSku sku = new GoodsSku();
		sku.setCostPrice(new BigDecimal("3.00"));
		cart.setGoodsSku(sku);
		assertNull(ShoppingCartSnapshot.of(List.of(cart)).toCarts().get(0).getGoodsSku().getCostPrice());
	}

	private ShoppingCartSnapshot roundTrip(List<ShoppingCart> carts) {
		Object restored = serializer.deserialize(serializer.serialize(ShoppingCartSnapshot.of(carts)));
		assertInstanceOf(ShoppingCartSnapshot.class, restored);
		return (ShoppingCartSnapshot) restored;
	}

	/** 反射把该类（含父类）每个实例字段填上不同的非空值；嵌套实体字段留给调用方设。遇到不认识的类型直接失败。 */
	private <T> T filled(Class<T> type) throws Exception {
		T bean = type.getDeclaredConstructor().newInstance();
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				Class<?> ft = field.getType();
				String key = type.getSimpleName() + "." + field.getName();
				boolean nestedEntity = ft == GoodsSpu.class || ft == GoodsSku.class || ft == ShopInfo.class;
				// 成本价照样填上：要证明它进不了缓存
				boolean excluded = NOT_IN_SNAPSHOT.contains(key) && !COST_PRICE.equals(key);
				if (nestedEntity || excluded) {
					continue;
				}
				field.setAccessible(true);
				field.set(bean, sample(key, ft));
			}
		}
		return bean;
	}

	private Object sample(String name, Class<?> ft) {
		int n = ++seq;
		if (ft == String.class) {
			return name + "-" + n;
		}
		if (ft == Integer.class) {
			return 1000 + n;
		}
		if (ft == Boolean.class) {
			return Boolean.TRUE;
		}
		if (ft == BigDecimal.class) {
			// 两位小数：小数位丢了 equals 就不等
			return new BigDecimal(n + ".10");
		}
		if (ft == LocalDateTime.class) {
			// 带微秒：PG timestamp 的精度，秒级 @JsonFormat 会丢
			return LocalDateTime.of(2026, 10, 23, 12, 0, n % 60, 123_456_000);
		}
		if (ft == String[].class) {
			return new String[] { name + "-a" + n, name + "-b" + n };
		}
		if (ft == List.class) {
			return new ArrayList<>(List.of(name + "-x" + n, name + "-y" + n));
		}
		fail("购物车快照不认识的字段类型 " + name + " : " + ft.getName()
				+ "——新加的标量字段要进 ShoppingCartSnapshot，嵌套对象要登记到 NOT_IN_SNAPSHOT 并确认列表查询不填它");
		return null;
	}
}
