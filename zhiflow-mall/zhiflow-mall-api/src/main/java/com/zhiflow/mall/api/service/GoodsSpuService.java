/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.api.vo.GoodsParameterItem;
import com.zhiflow.mall.common.dto.GoodsDetailAggregateDTO;
import com.zhiflow.mall.common.entity.GoodsSpu;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * spu商品
 *
 * @date 2019-08-12 16:25:10
 */
public interface GoodsSpuService extends IService<GoodsSpu> {

	/** 排序 tab：综合 / 销量 / 价格升序 / 价格降序 / 新品。 */
	String TAB_DEFAULT = "default";
	String TAB_SALES = "sales";
	String TAB_PRICE_ASC = "price_asc";
	String TAB_PRICE_DESC = "price_desc";
	String TAB_NEWEST = "newest";

	/** 商品详情：商品、SKU、品牌。查不到返回 null。 */
	GoodsSpu getById2Optimized(String id);

	/**
	 * 商品列表 / 搜索（只返回已上架商品）。MeiliSearch 启用且有关键词时查索引，否则或查索引失败时查库。
	 *
	 * @param goodsSpu 条件：name（关键词）、categoryFirst/Second/Third、brandId
	 * @param tab      排序 tab，空 = 综合
	 */
	IPage<LinkedHashMap> page(IPage<LinkedHashMap> page, GoodsSpu goodsSpu, BigDecimal minPrice, BigDecimal maxPrice, String tab);

	/** 销量原子累加。 */
	int increaseSaleNum(String spuId, int delta);

	/** 按 id 批量查已上架商品，按入参顺序返回（装修、推荐位按运营指定序展示）。 */
	List<LinkedHashMap> listGoodsSpuByIds(List<String> ids);

	/** 二级分类下的已上架商品。 */
	List<GoodsSpu> listCategorySpu(String categorySecondId);

	/** 多个二级分类下的已上架商品（一次 IN 查询）。 */
	List<GoodsSpu> listCategorySpuBatch(List<String> categoryIds);

	/** 商品详情页聚合：商品、规格树、店铺信息。商品不存在返回 null。 */
	GoodsDetailAggregateDTO getGoodsDetailAggregate(String id);

	/** 商详页参数区：按后台录入顺序返回参数名与展示值。商品不存在或未过审返回空列表。 */
	List<GoodsParameterItem> listParameters(String spuId);
}
