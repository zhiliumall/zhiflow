/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品sku规格值
 *
 * @date 2019-08-13 10:19:09
 */
public interface GoodsSkuSpecValueMapper extends BaseMapper<GoodsSkuSpecValue> {

	/**
	 * 批量按 skuId 查询规格值（含 specValueName），避免列表场景 N+1
	 */
	List<GoodsSkuSpecValue> listGoodsSkuSpecValueBySkuIds(@Param("skuIds") List<String> skuIds);

	/**
	 * 规格树各维度的取值（一个商品一条 SQL）：只填 specId、specValueId、specValueName。
	 */
	List<GoodsSkuSpecValue> listSpecLeafBySpuId(@Param("spuId") String spuId);
}
