/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.api.mapper.GoodsSkuMapper;
import com.zhiflow.mall.api.service.GoodsSkuService;
import com.zhiflow.common.lock.annotation.DistributedLock;
import com.zhiflow.common.lock.enums.LockTimeoutStrategy;
import org.springframework.stereotype.Service;

import java.io.Serializable;

/**
 * 商品sku
 *
 * @date 2019-08-13 10:18:34
 */
@Service
public class GoodsSkuServiceImpl extends ServiceImpl<GoodsSkuMapper, GoodsSku> implements GoodsSkuService {

	@Override
	@DistributedLock(name = "GoodsSku", keys = {"#entity.getId()"}, lockTimeoutStrategy = LockTimeoutStrategy.FAIL_FAST)
	public boolean updateById(GoodsSku entity) {
		return super.updateById(entity);
	}

	@Override
	@DistributedLock(name = "GoodsSku", keys = {"#updateWrapper.getEntity().getId()"}, lockTimeoutStrategy = LockTimeoutStrategy.FAIL_FAST)
	public boolean update(Wrapper<GoodsSku> updateWrapper) {
		return super.update(updateWrapper);
	}

	@Override
	@DistributedLock(name = "GoodsSku", keys = {"#id"}, lockTimeoutStrategy = LockTimeoutStrategy.FAIL_FAST)
	public boolean removeById(Serializable id) {
		return super.removeById(id);
	}
}
