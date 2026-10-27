/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.admin.controller;

import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字典接口的缓存 key 不能撞：内部接口返回 DTO、后台接口返回实体，两者共用一个缓存时 key 相同，
 * 先被调用的一方占住缓存，另一方取出来强转失败（实测：后台先打开发货抽屉加载字典，随后发货取快递公司字典 500）。
 */
class SysDictCacheKeyTest {

	@Test
	void cacheableMethodsInSameCacheUseDistinctKeys() {
		Map<String, String> seen = new HashMap<>();
		for (Method m : SysDictController.class.getDeclaredMethods()) {
			Cacheable c = m.getAnnotation(Cacheable.class);
			if (c == null) {
				continue;
			}
			String[] caches = c.value().length > 0 ? c.value() : c.cacheNames();
			for (String cache : caches) {
				String slot = cache + " | " + c.key();
				String prev = seen.putIfAbsent(slot, m.getName());
				assertThat(prev).as("%s 与 %s 用了同一个缓存 key：%s", prev, m.getName(), slot).isNull();
			}
		}
		assertThat(seen).hasSizeGreaterThanOrEqualTo(2);
	}
}
