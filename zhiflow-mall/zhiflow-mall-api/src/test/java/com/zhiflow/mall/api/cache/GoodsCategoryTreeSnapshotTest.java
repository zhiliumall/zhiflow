/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson2.JSON;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.cache.RedisTemplateConfig;
import com.zhiflow.mall.common.entity.GoodsCategoryTree;
import com.zhiflow.upms.common.util.TreeUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 分类树缓存值（{@code mall:goods_category#1800::tree}）：
 * 用生产序列化器往返后相等；还原出的接口返回值与直接查库组树得到的逐字节相同（ETag 就是对它算的 MD5）。
 */
class GoodsCategoryTreeSnapshotTest {

	private final GenericJacksonJsonRedisSerializer serializer = RedisTemplateConfig.createValkeyOptimizedJsonSerializer();

	@Test
	@DisplayName("经生产序列化器往返后逐字段相等")
	void roundTrips() {
		List<GoodsCategoryTreeSnapshot> snapshot = GoodsCategoryTreeSnapshot.of(tree());

		Object restored = serializer.deserialize(serializer.serialize(new ArrayList<>(snapshot)));

		assertInstanceOf(List.class, restored);
		assertEquals(snapshot, restored);
	}

	@Test
	@DisplayName("缓存往返后还原的返回值，与查库组树后 JSONUtil.parseObj 的结果序列化后完全相同（ETag 不变）")
	void restoredJsonIsIdentical() {
		List<JSONObject> fromDb = tree().stream().map(JSONUtil::parseObj).toList();
		@SuppressWarnings("unchecked")
		List<GoodsCategoryTreeSnapshot> cached = (List<GoodsCategoryTreeSnapshot>) serializer.deserialize(
				serializer.serialize(new ArrayList<>(GoodsCategoryTreeSnapshot.of(tree()))));

		assertEquals(JSON.toJSONString(fromDb), JSON.toJSONString(GoodsCategoryTreeSnapshot.toJson(cached)));
	}

	/** 与 GoodsCategoryServiceImpl#getTreeOptimized 一样组树：两个一级、其中一个带两个二级、一个二级再带三级。 */
	private static List<GoodsCategoryTree> tree() {
		List<GoodsCategoryTree> nodes = new ArrayList<>();
		nodes.add(node("1", "0", 1, "一级A", new String[0]));
		nodes.add(node("2", "0", 2, "一级B", null));
		nodes.add(node("11", "1", 1, "二级A1", new String[] { "H5", "APP" }));
		nodes.add(node("12", "1", 2, "二级A2", null));
		nodes.add(node("111", "11", 1, "三级", null));
		return TreeUtil.buildOptimized(nodes, CommonConstants.PARENT_ID);
	}

	private static GoodsCategoryTree node(String id, String parentId, int sort, String name, String[] platforms) {
		GoodsCategoryTree node = new GoodsCategoryTree();
		node.setId(id);
		node.setParentId(parentId);
		node.setSort(sort);
		node.setEnable("1");
		node.setName(name);
		node.setPicUrl("https://img/" + id + ".png");
		node.setSupportPlatforms(platforms);
		return node;
	}
}
