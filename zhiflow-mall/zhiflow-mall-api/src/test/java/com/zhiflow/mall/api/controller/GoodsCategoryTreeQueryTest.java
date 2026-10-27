/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.mall.common.entity.GoodsCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /goodscategory/tree 只有在请求没带任何筛选参数时才走缓存（查询条件直接由请求参数拼出）。 */
class GoodsCategoryTreeQueryTest {

	@Test
	@DisplayName("只有固定的 enable=1：整棵树，走缓存")
	void plainRequestUsesCache() {
		GoodsCategory query = new GoodsCategory();
		query.setEnable(CommonConstants.YES);
		assertTrue(GoodsCategoryController.isWholeTree(query));
	}

	@Test
	@DisplayName("带了任何筛选参数：照旧查库")
	void filteredRequestQueriesDatabase() {
		GoodsCategory byParent = new GoodsCategory();
		byParent.setEnable(CommonConstants.YES);
		byParent.setParentId("0");
		assertFalse(GoodsCategoryController.isWholeTree(byParent));

		GoodsCategory byName = new GoodsCategory();
		byName.setEnable(CommonConstants.YES);
		byName.setName("服装");
		assertFalse(GoodsCategoryController.isWholeTree(byName));

		GoodsCategory byInherited = new GoodsCategory();
		byInherited.setEnable(CommonConstants.YES);
		byInherited.setDelFlag("1");
		assertFalse(GoodsCategoryController.isWholeTree(byInherited), "父类字段（del_flag 等）同样算筛选");
	}
}
