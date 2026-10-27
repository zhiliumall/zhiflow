/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.mall.admin.mapper.GoodsSkuMapper;
import com.zhiflow.mall.admin.mapper.GoodsSkuSpecValueMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpecValueMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpuMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpuSpecMapper;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.GoodsSpuSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 后台改商品、SKU、规格之后，清用户端商品详情的两份缓存（{@code mall:goods_detail#600}、{@code mall:goods_stock#5}，
 * 读方见 mall-api {@code GoodsDetailCache}）。
 *
 * <p>用法分两步：先用 {@code of*} 查出受影响的商品（spuId → 商品行的 tenant_id），写库之后再 {@link #evictAfterCommit}。
 * 删除类操作必须在删之前查（软删之后 MyBatis-Plus 的查询就看不到这些行了）；
 * 在事务里时清除推迟到提交后，没有事务时当场清（写库已自动提交）。
 *
 * <p>规格、规格值这类跨商品的改动，按引用它们的商品逐个清，不用 allEntries（Spring Data Redis 里那是 KEYS）。
 * 每个后台写入口都要走这里，契约测试 {@code GoodsDetailCacheEvictContractTest} 守着。
 *
 * <p>顺带清用户端购物车缓存：{@link #evictAfterCommit(Map)} 末尾按受影响的商品清购物车里有它们的用户
 * （{@link ShoppingCartCacheEviction}，契约测试 {@code ShoppingCartCacheEvictContractTest}）——购物车展示的商品名、价格、
 * 图片、上下架、SKU 都来自这几张表，入口与商品详情完全相同，放在同一处就不会漏。
 */
@Component
@RequiredArgsConstructor
public class GoodsDetailCacheEviction {

	private final CacheManager cacheManager;
	private final GoodsSpuMapper goodsSpuMapper;
	private final GoodsSkuMapper goodsSkuMapper;
	private final GoodsSpuSpecMapper goodsSpuSpecMapper;
	private final GoodsSkuSpecValueMapper goodsSkuSpecValueMapper;
	private final GoodsSpecValueMapper goodsSpecValueMapper;
	private final ShoppingCartCacheEviction shoppingCartCacheEviction;

	/** 这些商品本身。 */
	public Map<String, String> ofSpus(Collection<String> spuIds) {
		Map<String, String> spus = new LinkedHashMap<>();
		List<String> ids = nonNull(spuIds);
		if (ids.isEmpty()) {
			return spus;
		}
		goodsSpuMapper.selectList(Wrappers.<GoodsSpu>lambdaQuery()
						.select(GoodsSpu::getId, GoodsSpu::getTenantId)
						.in(GoodsSpu::getId, ids))
				.forEach(spu -> spus.put(spu.getId(), spu.getTenantId()));
		return spus;
	}

	/** 这些 SKU 所属的商品。 */
	public Map<String, String> ofSkus(Collection<String> skuIds) {
		Map<String, String> spus = new LinkedHashMap<>();
		List<String> ids = nonNull(skuIds);
		if (ids.isEmpty()) {
			return spus;
		}
		goodsSkuMapper.selectList(Wrappers.<GoodsSku>lambdaQuery()
						.select(GoodsSku::getSpuId, GoodsSku::getTenantId)
						.in(GoodsSku::getId, ids))
				.forEach(sku -> spus.put(sku.getSpuId(), sku.getTenantId()));
		return spus;
	}

	/** 这些商品规格关联行（goods_spu_spec）所属的商品。 */
	public Map<String, String> ofSpuSpecs(Collection<String> spuSpecIds) {
		Map<String, String> spus = new LinkedHashMap<>();
		List<String> ids = nonNull(spuSpecIds);
		if (ids.isEmpty()) {
			return spus;
		}
		goodsSpuSpecMapper.selectList(Wrappers.<GoodsSpuSpec>lambdaQuery()
						.select(GoodsSpuSpec::getSpuId, GoodsSpuSpec::getTenantId)
						.in(GoodsSpuSpec::getId, ids))
				.forEach(row -> spus.put(row.getSpuId(), row.getTenantId()));
		return spus;
	}

	/** 用到这些规格的商品：规格树里有它的，加上 SKU 用了它下面某个规格值的。 */
	public Map<String, String> ofSpecs(Collection<String> specIds) {
		Map<String, String> spus = new LinkedHashMap<>();
		List<String> ids = nonNull(specIds);
		if (ids.isEmpty()) {
			return spus;
		}
		goodsSpuSpecMapper.selectList(Wrappers.<GoodsSpuSpec>lambdaQuery()
						.select(GoodsSpuSpec::getSpuId, GoodsSpuSpec::getTenantId)
						.in(GoodsSpuSpec::getSpecId, ids))
				.forEach(row -> spus.put(row.getSpuId(), row.getTenantId()));
		List<String> valueIds = goodsSpecValueMapper.selectList(Wrappers.<GoodsSpecValue>lambdaQuery()
						.select(GoodsSpecValue::getId)
						.in(GoodsSpecValue::getSpecId, ids))
				.stream().map(GoodsSpecValue::getId).toList();
		spus.putAll(ofSpecValues(valueIds));
		return spus;
	}

	/** SKU 用到这些规格值的商品。 */
	public Map<String, String> ofSpecValues(Collection<String> specValueIds) {
		Map<String, String> spus = new LinkedHashMap<>();
		List<String> ids = nonNull(specValueIds);
		if (ids.isEmpty()) {
			return spus;
		}
		goodsSkuSpecValueMapper.selectList(Wrappers.<GoodsSkuSpecValue>lambdaQuery()
						.select(GoodsSkuSpecValue::getSpuId, GoodsSkuSpecValue::getTenantId)
						.in(GoodsSkuSpecValue::getSpecValueId, ids))
				.forEach(row -> spus.put(row.getSpuId(), row.getTenantId()));
		return spus;
	}

	/** 已经拿到了商品行（含 tenant_id）时直接清，省一次查询。 */
	public void evictAfterCommit(GoodsSpu spu) {
		Map<String, String> spus = new LinkedHashMap<>();
		spus.put(spu.getId(), spu.getTenantId());
		evictAfterCommit(spus);
	}

	/**
	 * 提交后清这些商品的详情快照与库存缓存，按各自商品行的 tenant_id 解析缓存；
	 * 再清购物车里有这些商品的用户的购物车缓存（一条查询，按购物车行的 tenant_id 清）。
	 */
	public void evictAfterCommit(Map<String, String> spus) {
		spus.forEach((spuId, tenantId) -> {
			if (spuId == null) {
				return;
			}
			AfterCommitCacheEviction.evictForTenant(cacheManager, tenantId, CacheConstants.MALL_GOODS_DETAIL_CACHE, spuId);
			AfterCommitCacheEviction.evictForTenant(cacheManager, tenantId, CacheConstants.MALL_GOODS_STOCK_CACHE, spuId);
		});
		shoppingCartCacheEviction.evictCartsOfSpus(spus.keySet());
	}

	private static List<String> nonNull(Collection<String> ids) {
		return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
	}
}
