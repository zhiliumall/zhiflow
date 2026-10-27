/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpuSpec;
import com.zhiflow.mall.common.entity.GoodsSpec;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品sku规格值
 *
 * @date 2019-08-13 10:19:09
 */
public interface GoodsSkuSpecValueMapper extends BaseMapper<GoodsSkuSpecValue> {

	List<GoodsSkuSpecValue> listGoodsSkuSpecValueBySkuId(@Param("skuIds") List<String> skuIds);
	List<GoodsSkuSpecValue> listGoodsSkuSpecValueBySku(@Param("skuId") String skuId);

	List<GoodsSpec> selectBySpecId(GoodsSpuSpec goodsSpuSpec);
}
