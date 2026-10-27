/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.admin.mapper.GoodsSkuMapper;
import com.zhiflow.mall.admin.service.GoodsSkuService;
import com.zhiflow.common.lock.annotation.DistributedLock;
import com.zhiflow.common.lock.enums.LockTimeoutStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品sku
 *
 * <p>每个写方法都在写库后（有事务时为提交后）清所属商品的详情与库存缓存，见 {@link GoodsDetailCacheEviction}。
 *
 * @date 2019-08-13 10:18:34
 */
@Service
@RequiredArgsConstructor
public class GoodsSkuServiceImpl extends ServiceImpl<GoodsSkuMapper, GoodsSku> implements GoodsSkuService {

	private final GoodsDetailCacheEviction goodsDetailCacheEviction;

	/**
	 * 刚插入的行：spu_id 取实体上的；tenant_id 由租户插件写进 SQL、实体上可能为空，为空时按当前租户清——
	 * 插件写进去的正是当前租户，与数据行一致。
	 */
	@Override
	public boolean save(GoodsSku entity) {
		boolean saved = super.save(entity);
		Map<String, String> spus = new LinkedHashMap<>();
		spus.put(entity.getSpuId(), entity.getTenantId());
		goodsDetailCacheEviction.evictAfterCommit(spus);
		return saved;
	}

	@Override
	@DistributedLock(name = "GoodsSku", keys = {"#entity.getId()"}, lockTimeoutStrategy = LockTimeoutStrategy.FAIL_FAST)
	public boolean updateById(GoodsSku entity) {
		List<String> ids = Collections.singletonList(entity.getId());
		// 改前改后各取一次所属商品：请求可能把 SKU 挪到别的商品，原商品与新商品都要清
		Map<String, String> spus = goodsDetailCacheEviction.ofSkus(ids);
		boolean updated = super.updateById(entity);
		spus.putAll(goodsDetailCacheEviction.ofSkus(ids));
		goodsDetailCacheEviction.evictAfterCommit(spus);
		return updated;
	}

	/** 只认带实体 id 的 wrapper（与分布式锁的 key 同一个前提）；实体或 id 为空时清不到，也锁不到。 */
	@Override
	@DistributedLock(name = "GoodsSku", keys = {"#updateWrapper.getEntity().getId()"}, lockTimeoutStrategy = LockTimeoutStrategy.FAIL_FAST)
	public boolean update(Wrapper<GoodsSku> updateWrapper) {
		GoodsSku entity = updateWrapper.getEntity();
		List<String> ids = Collections.singletonList(entity == null ? null : entity.getId());
		// 改前改后各取一次所属商品，理由同 updateById
		Map<String, String> spus = goodsDetailCacheEviction.ofSkus(ids);
		boolean updated = super.update(updateWrapper);
		spus.putAll(goodsDetailCacheEviction.ofSkus(ids));
		goodsDetailCacheEviction.evictAfterCommit(spus);
		return updated;
	}

	@Override
	@DistributedLock(name = "GoodsSku", keys = {"#id"}, lockTimeoutStrategy = LockTimeoutStrategy.FAIL_FAST)
	public boolean removeById(Serializable id) {
		// 删之前查：软删之后就查不到这个 SKU 属于哪个商品了
		Map<String, String> spus = goodsDetailCacheEviction.ofSkus(List.of(String.valueOf(id)));
		boolean removed = super.removeById(id);
		goodsDetailCacheEviction.evictAfterCommit(spus);
		return removed;
	}

	@Override
	public List<GoodsSku> listGoodsSkuBySpuId(String spuId) {
		return baseMapper.listGoodsSkuBySpuId(spuId);
	}
}
