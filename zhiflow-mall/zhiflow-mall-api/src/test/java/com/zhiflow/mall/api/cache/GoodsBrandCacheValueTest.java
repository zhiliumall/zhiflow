/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import com.zhiflow.common.data.cache.RedisTemplateConfig;
import com.zhiflow.mall.common.entity.GoodsBrand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 品牌缓存（{@code mall:goods_brand#1800}）直接存 {@link GoodsBrand} 实体（没有 {@code @HashidsId}）：
 * 用生产序列化器写进去、读出来要相等，否则缓存恒为 0 命中且不报错。
 *
 * <p>时间字段带 {@code @JsonFormat("yyyy-MM-dd HH:mm:ss")}，缓存里只存到秒——与接口输出的精度相同，
 * 所以这里用整秒；毫秒在缓存里被截掉不影响任何输出。
 */
class GoodsBrandCacheValueTest {

	private final GenericJacksonJsonRedisSerializer serializer = RedisTemplateConfig.createValkeyOptimizedJsonSerializer();

	@Test
	@DisplayName("品牌实体经生产序列化器往返后逐字段相等（含 bpchar 补位的 del_flag）")
	void brandRoundTrips() {
		GoodsBrand brand = new GoodsBrand();
		brand.setId("2113259317361639426");
		brand.setTenantId("1");
		brand.setDelFlag("0 ");
		brand.setCreateTime(LocalDateTime.of(2026, 10, 22, 21, 20, 58));
		brand.setUpdateTime(LocalDateTime.of(2026, 10, 22, 21, 21, 0));
		brand.setSort(1);
		brand.setEnable("1");
		brand.setBrandName("测试品牌");
		brand.setPicUrl(null);

		Object restored = serializer.deserialize(serializer.serialize(brand));

		assertInstanceOf(GoodsBrand.class, restored);
		assertEquals(brand, restored);
	}
}
