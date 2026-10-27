/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.dto.PlaceOrderDTO;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderItem;
import com.zhiflow.mall.common.entity.UserAddress;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.mall.common.enums.OrderRecordTypeEnum;
import jakarta.servlet.http.HttpServletRequest;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 商城订单
 *
 * @date 2019-09-10 15:21:22
 */
public interface OrderInfoService extends IService<OrderInfo> {

	/**
	 * 新增订单
	 * @param placeOrderDTO
	 */
	OrderInfo orderAddOne(HttpServletRequest request, PlaceOrderDTO placeOrderDTO) throws Exception;

	/**
	 * 新增订单集合
	 * @param mapPlaceOrderDTO
	 */
	List<OrderInfo> orderAddMap(HttpServletRequest request, Map<String, PlaceOrderDTO> mapPlaceOrderDTO) throws Exception;

	/**
	 * 下单收货地址归属校验：快递 / 加急配送（要用收货地址的两种配送方式）下，
	 * {@code userAddressId} 必须指向一条存在、且属于下单人（{@code placeOrderDTO.userId}）的地址。
	 * <p>调用方须先把 {@code placeOrderDTO.userId} 定成会话用户（同步入口）或同步阶段已定好的用户（秒杀异步消费）。
	 * @return 校验通过的地址；不需要收货地址的配送方式返回 null
	 * @throws com.zhiflow.common.core.exception.BizException 地址缺失 / 不存在（「请选择收货地址」）、不属于下单人（「无权操作」）
	 */
	UserAddress requireOwnShippingAddress(PlaceOrderDTO placeOrderDTO);

	IPage<OrderInfo> page2(IPage<OrderInfo> page, OrderInfo orderInfo);

	OrderInfo getById2(Serializable id);

	/**
	 * 取消订单
	 * @param orderInfo
	 */
	void orderCancel(OrderInfo orderInfo, OptSourceEnum optSource);
	/**
	 * 订单收货
	 * @param orderInfo
	 */
	void orderReceive(OrderInfo orderInfo, OptSourceEnum optSource);

	/**
	 * 处理订单支付回调
	 * @param orderInfo
	 */
	void notifyOrder(OrderInfo orderInfo);

	void saveOrderRecord(String orderId, OrderRecordTypeEnum recordType, OptSourceEnum recordSource);

    /**
     * 订单取消
     * @param orderId
     * @param optSource
     */
   void orderCancelV2(String orderId, OptSourceEnum optSource);
}
