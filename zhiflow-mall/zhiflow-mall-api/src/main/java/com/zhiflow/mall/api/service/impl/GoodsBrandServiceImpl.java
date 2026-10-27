/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.mall.common.entity.GoodsBrand;
import com.zhiflow.mall.api.mapper.GoodsBrandMapper;
import com.zhiflow.mall.api.service.GoodsBrandService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.Serializable;

/**
 * 商品品牌
 *
 * @date 2024-09-02 10:45:39
 */
@Service
public class GoodsBrandServiceImpl extends ServiceImpl<GoodsBrandMapper, GoodsBrand> implements GoodsBrandService {

	/**
	 * 商品详情与 /goodsbrand/{id} 共用，30 分钟；后台品牌改、删后提交后清（mall-admin GoodsBrandServiceImpl）。
	 * 品牌实体没有 {@code @HashidsId}，可以直接缓存。
	 */
	@Override
	@Cacheable(value = CacheConstants.MALL_GOODS_BRAND_CACHE, key = "#id",
			condition = CacheConstants.TENANT_PRESENT, unless = "#result == null")
	public GoodsBrand getById(Serializable id) {
		return super.getById(id);
	}
}
