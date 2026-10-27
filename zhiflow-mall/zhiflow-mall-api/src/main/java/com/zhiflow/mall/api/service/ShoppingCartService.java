/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.ShoppingCart;

import java.util.List;

/**
 * 购物车
 *
 * @date 2019-08-29 21:27:33
 */
public interface ShoppingCartService extends IService<ShoppingCart> {

	/** 当前用户的购物车（已过滤下架商品、停用店铺、已删规格） */
	List<ShoppingCart> listCart(ShoppingCart shoppingCart);

	int countByCondition(ShoppingCart shoppingCart);
}
