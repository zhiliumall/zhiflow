/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.ShopInfo;

/**
 * 店铺表
 *
 * @date 2020-05-15 13:35:18
 */
public interface ShopInfoService extends IService<ShopInfo> {

	/**
	 * 改商城设置：只改名称、logo、客服电话，其余字段（启用状态等）不随请求体变化
	 */
	boolean updateSettings(ShopInfo settings);
}
