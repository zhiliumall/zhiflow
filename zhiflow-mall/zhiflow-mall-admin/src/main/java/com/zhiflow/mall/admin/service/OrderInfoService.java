/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.dto.OrderDeliveryDTO;
import com.zhiflow.mall.common.dto.OrderTotalDTO;
import com.zhiflow.mall.common.dto.OrderRemarkBatchRequest;
import com.zhiflow.mall.common.dto.OrderRemarkBatchResult;
import com.zhiflow.mall.common.dto.PlaceOrderDTO;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderItem;
import jakarta.servlet.http.HttpServletRequest;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 商城订单
 *
 * @date 2019-09-10 15:21:22
 */
public interface OrderInfoService extends IService<OrderInfo> {
	OrderInfo getById1(Serializable id);

	IPage<OrderInfo> page1(IPage<OrderInfo> page, Wrapper<OrderInfo> queryWrapper);

	/**
	 * 查询统计付款金额
	 * @param orderInfo
	 * @return
	 */
	BigDecimal sumPaymentPrice(OrderInfo orderInfo);

	/**
	 * 订单发货
	 * @param orderDeliveryDTO
	 */
	void delivery(OrderDeliveryDTO orderDeliveryDTO);

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
	Map<String, Long> getOrderCountsByStatus(String shopId);

	/** Updates a validated remark for each accessible order and reports each outcome. */
	OrderRemarkBatchResult batchRemark(OrderRemarkBatchRequest request, String shopId);
}
