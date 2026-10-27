/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.dto.PlaceOrderSkuDTO;
import com.zhiflow.mall.common.entity.GoodsSku;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品sku
 *
 * @date 2019-08-13 10:18:34
 */
public interface GoodsSkuMapper extends BaseMapper<GoodsSku> {

	List<GoodsSku> listGoodsSkuBySpuId(String spuId);

	GoodsSku selectOneByShoppingCart(String id);

	GoodsSku getGoodsSkuById(String id);

	List<GoodsSku> listSkuInOrderSub(@Param("shopId")String shopId , @Param("listPlaceOrderSkuDTO")List<PlaceOrderSkuDTO> listPlaceOrderSkuDTO);
}
