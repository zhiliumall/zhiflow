/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.api.mapper.GoodsSkuMapper;
import com.zhiflow.mall.api.mapper.GoodsSkuSpecValueMapper;
import com.zhiflow.mall.api.mapper.GoodsSpuMapper;
import com.zhiflow.mall.api.service.GoodsSpuSpecService;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.GoodsSpuSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 商品详情的两份缓存（键都是 {@code {tenantId}:{缓存名}::{spuId}}）：
 * <ul>
 * <li>{@link #getDetail} 静态快照，10 分钟；未命中时 4 条 SQL（商品、在售 SKU、全部 SKU 的规格值、规格树 2 条），
 *     与 SKU 数、规格维度数无关。默认配置给它开了进程内 L1（{@code zhiflow.perf.redis-l1.cache-names}）：
 *     命中时多个请求共用同一个快照对象，读方只能经 {@code toGoodsSpu / toSpecTree} 转出新对象再改；</li>
 * <li>{@link #getStock} 库存与销量，5 秒；未命中时 1 条 SQL；同一实例同一商品同一时刻只放一个请求去读（单飞）。</li>
 * </ul>
 * 失效：后台改商品、SKU、规格的入口在事务提交后按 spuId 清（mall-admin {@code GoodsDetailCacheEviction}）。
 * 下单、支付、取消一般不清，库存最多晚 5 秒展示；只有某 SKU 扣后剩余 ≤ 低库存阈值时，下单提交后清库存这一份
 * （{@code OrderStockDeduction}）。下单校验永远以数据库为准，不读这里。
 *
 * <p>拿不到租户时不走缓存（{@link CacheConstants#TENANT_PRESENT}）。
 */
@Service
@RequiredArgsConstructor
public class GoodsDetailCache {

	private final GoodsSpuMapper goodsSpuMapper;
	private final GoodsSkuMapper goodsSkuMapper;
	private final GoodsSkuSpecValueMapper goodsSkuSpecValueMapper;
	private final GoodsSpuSpecService goodsSpuSpecService;
	private final GoodsStockCache goodsStockCache;

	/** 跟随者最多等领头的多久，超时自己去读（不无限等一个卡住的请求）。 */
	static final long FOLLOWER_WAIT_MS = 2000;

	/** 在途的库存读取：键 = 租户 + spuId。只在读取期间存在，读完即删，不是缓存。 */
	private final ConcurrentHashMap<String, CompletableFuture<GoodsStockSnapshot>> stockLoads = new ConcurrentHashMap<>();

	/** 已过审的商品（含已下架，详情页仍能打开）；查不到返回 null，不缓存。 */
	@Cacheable(value = CacheConstants.MALL_GOODS_DETAIL_CACHE, key = "#spuId",
			condition = CacheConstants.TENANT_PRESENT, unless = "#result == null")
	public GoodsDetailSnapshot getDetail(String spuId) {
		GoodsSpu spu = goodsSpuMapper.selectById2(spuId);
		if (spu == null) {
			return null;
		}
		List<GoodsSku> skus = goodsSkuMapper.listDetailSkuBySpuId(spuId);
		Map<String, List<GoodsSkuSpecValue>> specsBySku = new HashMap<>();
		if (!skus.isEmpty()) {
			List<String> skuIds = skus.stream().map(GoodsSku::getId).toList();
			// 按 sort 排序后逐个归组，每个 SKU 内的顺序与原来逐个查（ORDER BY sort）一致
			for (GoodsSkuSpecValue value : goodsSkuSpecValueMapper.listGoodsSkuSpecValueBySkuIds(skuIds)) {
				specsBySku.computeIfAbsent(value.getSkuId(), k -> new ArrayList<>()).add(value);
			}
		}
		GoodsSpuSpec specQuery = new GoodsSpuSpec();
		specQuery.setSpuId(spuId);
		return GoodsDetailSnapshot.of(spu, skus, specsBySku, goodsSpuSpecService.tree(specQuery));
	}

	/**
	 * 库存与销量（商品不存在返回 null）。进程内单飞：同一租户同一商品同一时刻只有一个请求去读
	 * {@link GoodsStockCache}（命中读 valkey，未命中回库并写回），其余请求等它的结果，5 秒一过期不会一波请求全部回库。
	 *
	 * <p>为什么不用 {@code @Cacheable(sync = true)}：它不能与 {@code unless} 同用；而且 Spring Data Redis 4.1 的
	 * {@code RedisCache#get(key, loader)} 直接委托 writer，没有任何进程内锁，sync 在这里起不到单飞作用。
	 *
	 * <p>跟随者最多等 {@link #FOLLOWER_WAIT_MS}，超时自己去读；领头的抛异常，同一时刻的跟随者拿到同一个异常。
	 * 并发的请求共用同一个结果对象，读方（{@code GoodsDetailSnapshot#toGoodsSpu}）只读不改。拿不到租户时不单飞。
	 */
	public GoodsStockSnapshot getStock(String spuId) {
		String tenantId = TenantContextHolder.getTenantId();
		if (tenantId == null) {
			return goodsStockCache.get(spuId);
		}
		String key = tenantId + ":" + spuId;
		CompletableFuture<GoodsStockSnapshot> mine = new CompletableFuture<>();
		CompletableFuture<GoodsStockSnapshot> leader = stockLoads.putIfAbsent(key, mine);
		if (leader != null) {
			return awaitLeader(leader, spuId);
		}
		try {
			GoodsStockSnapshot stock = goodsStockCache.get(spuId);
			mine.complete(stock);
			return stock;
		}
		catch (RuntimeException | Error e) {
			mine.completeExceptionally(e);
			throw e;
		}
		finally {
			stockLoads.remove(key, mine);
		}
	}

	private GoodsStockSnapshot awaitLeader(CompletableFuture<GoodsStockSnapshot> leader, String spuId) {
		try {
			return leader.get(FOLLOWER_WAIT_MS, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			return goodsStockCache.get(spuId);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return goodsStockCache.get(spuId);
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException(cause);
		}
	}
}
