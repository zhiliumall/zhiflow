/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.mall.admin.mapper.BannerMapper;
import com.zhiflow.mall.admin.mapper.GlobalHotWordMapper;
import com.zhiflow.mall.admin.service.BannerService;
import com.zhiflow.mall.admin.service.GlobalHotWordService;
import com.zhiflow.common.test.WriteEvictScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.cache.interceptor.SimpleKeyGenerator;

import java.util.Map;
import java.util.Set;

import static com.zhiflow.common.test.WriteEvictScanner.internal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台写轮播、热搜词的每个入口都必须提交后清对应的用户端缓存（原来只靠 60 秒过期）。判据见 {@link WriteEvictScanner}。
 */
class BannerHotWordCacheEvictContractTest {

	private static WriteEvictScanner scanner;

	@BeforeAll
	static void scan() {
		scanner = WriteEvictScanner.scan("com.zhiflow.mall.admin");
	}

	@Test
	@DisplayName("轮播：增删改都清 mall:banner:enabled#60；对照组必须报出三个入口")
	void bannerWritesEvict() {
		Set<String> impls = Set.of(internal(BannerServiceImpl.class));
		Set<String> owners = Set.of(internal(BannerServiceImpl.class), internal(BannerService.class), internal(BannerMapper.class));

		Set<String> violations = scanner.uncovered(owners, impls, method -> method.invokes(
				internal(AfterCommitCacheEviction.class), "evictForTenant", CacheConstants.MALL_BANNER_CACHE), Map.of());
		assertTrue(violations.isEmpty(), "以下写入口没有清轮播缓存：\n  " + String.join("\n  ", violations));

		Set<String> control = scanner.uncovered(owners, impls, method -> false, Map.of());
		for (String known : Set.of("BannerController#save", "BannerController#updateById", "BannerController#removeById")) {
			assertTrue(control.stream().anyMatch(v -> v.startsWith(known + " ")), "对照组没报出 " + known + "：" + control);
		}
	}

	@Test
	@DisplayName("热搜词：增删改都清 mall:hotword:top#60；对照组必须报出三个入口")
	void hotWordWritesEvict() {
		Set<String> impls = Set.of(internal(GlobalHotWordServiceImpl.class));
		Set<String> owners = Set.of(internal(GlobalHotWordServiceImpl.class), internal(GlobalHotWordService.class),
				internal(GlobalHotWordMapper.class));

		Set<String> violations = scanner.uncovered(owners, impls, method -> method.invokes(
				internal(AfterCommitCacheEviction.class), "evictForTenant", CacheConstants.MALL_HOTWORD_CACHE), Map.of());
		assertTrue(violations.isEmpty(), "以下写入口没有清热词缓存：\n  " + String.join("\n  ", violations));

		Set<String> control = scanner.uncovered(owners, impls, method -> false, Map.of());
		for (String known : Set.of("HotWordController#save", "HotWordController#updateById", "HotWordController#removeById")) {
			assertTrue(control.stream().anyMatch(v -> v.startsWith(known + " ")), "对照组没报出 " + known + "：" + control);
		}
	}

	@Test
	@DisplayName("清的键与用户端无参 @Cacheable 生成的键是同一个（SimpleKey []）")
	void evictedKeyMatchesNoArgCacheableKey() {
		assertEquals(SimpleKeyGenerator.generateKey(), SimpleKey.EMPTY);
		assertEquals("SimpleKey []", SimpleKey.EMPTY.toString());
	}
}
