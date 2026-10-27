/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.mall.common.entity.OrderItem;
import com.zhiflow.mall.admin.mapper.OrderItemMapper;
import com.zhiflow.mall.admin.service.OrderItemService;
import org.springframework.stereotype.Service;

import java.io.Serializable;

/**
 * 商城订单详情
 *
 * @date 2019-09-10 15:31:40
 */
@Service
public class OrderItemServiceImpl extends ServiceImpl<OrderItemMapper, OrderItem> implements OrderItemService {

	@Override
	public OrderItem getById2(Serializable id) {
		return baseMapper.selectById2(id);
	}

	@Override
	public int markItemsShipped(String orderId, java.util.Collection<String> orderItemIds) {
		return orderItemIds == null || orderItemIds.isEmpty() ? 0 : baseMapper.markShipped(orderId, orderItemIds);
	}
}
