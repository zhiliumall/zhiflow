/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.api.cache.GoodsStockSnapshot;
import com.zhiflow.mall.common.entity.GoodsSpu;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * spu商品
 *
 * @date 2019-08-12 16:25:10
 */
public interface GoodsSpuMapper extends BaseMapper<GoodsSpu> {

	/** 商品详情（不含 SKU），只查已过审的商品。 */
	GoodsSpu selectById2(String id);

	/** 商品销量与全部在售 SKU 的库存、版本号；商品不存在时返回 null。 */
	GoodsStockSnapshot selectStockBySpuId(String spuId);

	/** 购物车联表用的商品摘要（ShoppingCartMapper.xml 嵌套 select 引用）。 */
	GoodsSpu selectOneByShoppingCart(String id);

	@Update("UPDATE goods_spu SET sale_num = sale_num + #{delta} WHERE id = #{spuId}")
	int increaseSaleNum(@Param("spuId") String spuId, @Param("delta") int delta);
}
