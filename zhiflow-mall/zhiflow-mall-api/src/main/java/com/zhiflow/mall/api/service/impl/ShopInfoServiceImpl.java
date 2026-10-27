/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.mall.common.entity.ShopInfo;
import com.zhiflow.mall.api.mapper.ShopInfoMapper;
import com.zhiflow.mall.api.service.ShopInfoService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.Serializable;

/**
 * 店铺表
 *
 * @date 2020-05-15 14:35:53
 */
@Service
public class ShopInfoServiceImpl extends ServiceImpl<ShopInfoMapper, ShopInfo> implements ShopInfoService {

	@Override
	@Cacheable(value = CacheConstants.MALL_SHOP_INFO_CACHE, key = "#id", unless = "#result == null")
	public ShopInfo getById(Serializable id) {
		return super.getById(id);
	}
}
