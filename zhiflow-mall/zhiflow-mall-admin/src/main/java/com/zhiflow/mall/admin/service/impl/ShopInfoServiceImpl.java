/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.mall.common.entity.ShopInfo;
import com.zhiflow.mall.admin.mapper.ShopInfoMapper;
import com.zhiflow.mall.admin.service.ShopInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.Serializable;

/**
 * 店铺表
 *
 * <p>购物车展示里有店铺名、logo、启用状态：改 / 删店铺后清购物车里有该店铺商品的用户的购物车缓存
 * （{@link ShoppingCartCacheEviction}）。
 *
 * @date 2020-05-15 13:35:18
 */
@Service
@RequiredArgsConstructor
public class ShopInfoServiceImpl extends ServiceImpl<ShopInfoMapper, ShopInfo> implements ShopInfoService {

	private final ShoppingCartCacheEviction shoppingCartCacheEviction;

	@Override
	@Cacheable(value = CacheConstants.MALL_SHOP_INFO_CACHE, key = "#id", unless = "#result == null")
	public ShopInfo getById(Serializable id) {
		return super.getById(id);
	}

	@Override
	@CacheEvict(value = CacheConstants.MALL_SHOP_INFO_CACHE, key = "#entity.id")
	public boolean updateById(ShopInfo entity) {
		boolean updated = super.updateById(entity);
		shoppingCartCacheEviction.evictCartsOfShop(entity.getId());
		return updated;
	}

	@Override
	@CacheEvict(value = CacheConstants.MALL_SHOP_INFO_CACHE, key = "#id")
	public boolean removeById(Serializable id) {
		boolean removed = super.removeById(id);
		shoppingCartCacheEviction.evictCartsOfShop(id);
		return removed;
	}

	@Override
	@CacheEvict(value = CacheConstants.MALL_SHOP_INFO_CACHE, key = "#settings.id")
	public boolean updateSettings(ShopInfo settings) {
		boolean updated = this.lambdaUpdate()
			.set(ShopInfo::getName, settings.getName())
			.set(ShopInfo::getImgUrl, settings.getImgUrl())
			.set(ShopInfo::getPhone, settings.getPhone())
			.eq(ShopInfo::getId, settings.getId())
			.update();
		shoppingCartCacheEviction.evictCartsOfShop(settings.getId());
		return updated;
	}
}
