/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.OrderItem;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商城订单详情
 *
 * @date 2019-09-10 15:31:40
 */
public interface OrderItemService extends IService<OrderItem> {

	OrderItem getById2(Serializable id);

	/** 发货 / 自提核销——列出的项记发货时刻，NORMAL 的改 SHIPPED。 */
	int markItemsShipped(String orderId, java.util.Collection<String> orderItemIds);
}
