/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 商品卡片规格(独立新增,不改动现有 mapper)。
 * 批量取每个 SPU 的代表规格文案,供列表卡片展示。
 *
 */
@Mapper
public interface CardSpecMapper {

	/**
	 * 批量查询多个 SPU 的代表规格文案(取最低价 SKU 的规格值按 sort 拼接,如 "7.5mg×2支")。
	 *
	 * @param spuIds 真实 SPU id 列表(已解码)
	 * @return 每行 {spu_id, spec_text}
	 */
	List<Map<String, Object>> selectSellSpecBySpuIds(@Param("spuIds") List<String> spuIds);
}
