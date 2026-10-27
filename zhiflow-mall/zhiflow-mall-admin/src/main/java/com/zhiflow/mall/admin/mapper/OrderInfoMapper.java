/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zhiflow.mall.common.dto.OrderTotalDTO;
import com.zhiflow.mall.common.entity.OrderInfo;
import org.apache.ibatis.annotations.Param;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商城订单
 *
 * @date 2019-09-10 15:21:22
 */
public interface OrderInfoMapper extends BaseMapper<OrderInfo> {

	IPage<OrderInfo> selectPage1(IPage<OrderInfo> page, @Param("query") OrderInfo orderInfo);
	BigDecimal sumPaymentPrice(@Param("query") OrderInfo orderInfo);

	OrderInfo selectById1(Serializable id);

	/**
	 * 订单量商品排行TOP10
	 * @return
	 */
	List<Map<String, Integer>> statisticsOrderCountSpu(@Param("query") OrderInfo orderInfo);
	/**
	 * 订单量店铺排行
	 * @return
	 */
	List<Map<String, Integer>> statisticsOrderCountShopInfo(@Param("query") OrderInfo orderInfo);
	/**
	 * 订单量分类品牌排行
	 * @return
	 */
	List<Map<String, Integer>> statisticsOrderCountCategory(@Param("query") OrderInfo orderInfo);

	/**
	 * 金额商品排行TOP10
	 * @return
	 */
	List<Map<String, Integer>> statisticsSaleAmountSpu(@Param("query") OrderInfo orderInfo);
	/**
	 * 金额店铺排行
	 * @return
	 */
	List<Map<String, Integer>> statisticsSaleAmountShopInfo(@Param("query") OrderInfo orderInfo);
	/**
	 * 金额分类排行
	 * @return
	 */
	List<Map<String, Integer>> statisticsSaleAmountCategory(@Param("query") OrderInfo orderInfo);

	/**
	 * 商品销量排行TOP10
	 * @return
	 */
	List<Map<String, Integer>> statisticsSpuSale(@Param("query") OrderInfo orderInfo);
	/**
	 * 商品销量店铺排行
	 * @return
	 */
	List<Map<String, Integer>> statisticsSpuSaleShopInfo(@Param("query") OrderInfo orderInfo);
	/**
	 * 商品销量分类品牌排行
	 * @return
	 */
	List<Map<String, Integer>> statisticsSpuSaleCategory(@Param("query") OrderInfo orderInfo);

	/**
	 * 统计订单金额和数量
	 * @param userId 用户id
	 * @return
	 */
	OrderTotalDTO sumOrderAmountAndCountByUserId(String userId);

	/**
	 * 批量统计各个状态的订单数量（优化版本）
	 * 使用 GROUP BY 一次性查询所有状态的订单数量，替代多次独立查询
	 * 
	 * @param shopId 店铺ID（可选）
	 * @return Map<状态, 数量>
	 */
	List<Map<String, Object>> selectOrderCountsByStatus(@Param("shopId") String shopId);

	List<Map<String, Object>> statisticsMonthlySales(@Param("query") OrderInfo orderInfo);

	List<Map<String, Object>> statisticsProvinceSales(@Param("query") OrderInfo orderInfo);

	List<Map<String, Object>> statisticsDailyTrend(@Param("query") OrderInfo orderInfo);

	List<Map<String, Object>> statisticsChannel(@Param("query") OrderInfo orderInfo);
}
