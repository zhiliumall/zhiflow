/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import com.zhiflow.common.data.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GoodsDetailCache#getStock} 的进程内单飞：同租户同商品同一时刻只有一个请求去读库存缓存 / 回库。
 */
class GoodsStockSingleFlightTest {

	private static final int THREADS = 8;

	private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

	@AfterEach
	void tearDown() throws InterruptedException {
		TenantContextHolder.clear();
		pool.shutdownNow();
		pool.awaitTermination(5, TimeUnit.SECONDS);
	}

	/** 第一次调用卡在闸门上，直到所有线程都已发起请求；统计真正读了几次。 */
	private static final class GatedStockCache extends GoodsStockCache {

		final AtomicInteger loads = new AtomicInteger();

		final CountDownLatch release = new CountDownLatch(1);

		volatile RuntimeException failure;

		GatedStockCache() {
			super(null);
		}

		@Override
		public GoodsStockSnapshot get(String spuId) {
			loads.incrementAndGet();
			try {
				assertTrue(release.await(5, TimeUnit.SECONDS));
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (failure != null) {
				throw failure;
			}
			GoodsStockSnapshot stock = new GoodsStockSnapshot();
			stock.setSpuId(spuId);
			return stock;
		}
	}

	private List<Future<GoodsStockSnapshot>> fire(GoodsDetailCache cache, String tenant, String spuId,
			CountDownLatch started) {
		List<Future<GoodsStockSnapshot>> futures = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			futures.add(pool.submit((Callable<GoodsStockSnapshot>) () -> {
				TenantContextHolder.setTenantId(tenant);
				started.countDown();
				try {
					return cache.getStock(spuId);
				}
				finally {
					TenantContextHolder.clear();
				}
			}));
		}
		return futures;
	}

	@Test
	@DisplayName("同租户同商品并发 8 个请求：只读 1 次，8 个拿到同一个结果")
	void concurrentReadsCollapseIntoOne() throws Exception {
		GatedStockCache stockCache = new GatedStockCache();
		GoodsDetailCache cache = new GoodsDetailCache(null, null, null, null, stockCache);
		CountDownLatch started = new CountDownLatch(THREADS);

		List<Future<GoodsStockSnapshot>> futures = fire(cache, "1", "100", started);
		assertTrue(started.await(5, TimeUnit.SECONDS));
		// 给跟随者一点时间挂到领头的 future 上
		Thread.sleep(200);
		stockCache.release.countDown();

		GoodsStockSnapshot first = futures.get(0).get(5, TimeUnit.SECONDS);
		for (Future<GoodsStockSnapshot> future : futures) {
			assertSame(first, future.get(5, TimeUnit.SECONDS));
		}
		assertEquals(1, stockCache.loads.get());

		// 读完即删：下一次重新读
		TenantContextHolder.setTenantId("1");
		cache.getStock("100");
		assertEquals(2, stockCache.loads.get());
	}

	@Test
	@DisplayName("不同租户同一 spuId 不合并（键带租户）")
	void differentTenantsAreSeparate() throws Exception {
		GatedStockCache stockCache = new GatedStockCache();
		GoodsDetailCache cache = new GoodsDetailCache(null, null, null, null, stockCache);
		CountDownLatch started = new CountDownLatch(2);
		Future<GoodsStockSnapshot> a = pool.submit(() -> {
			TenantContextHolder.setTenantId("1");
			started.countDown();
			return cache.getStock("100");
		});
		Future<GoodsStockSnapshot> b = pool.submit(() -> {
			TenantContextHolder.setTenantId("2");
			started.countDown();
			return cache.getStock("100");
		});
		assertTrue(started.await(5, TimeUnit.SECONDS));
		Thread.sleep(200);
		stockCache.release.countDown();
		a.get(5, TimeUnit.SECONDS);
		b.get(5, TimeUnit.SECONDS);

		assertEquals(2, stockCache.loads.get());
	}

	@Test
	@DisplayName("领头的抛异常：同一时刻的跟随者拿到同一个异常；之后不残留在途项")
	void leaderFailurePropagates() throws Exception {
		GatedStockCache stockCache = new GatedStockCache();
		stockCache.failure = new IllegalStateException("db down");
		GoodsDetailCache cache = new GoodsDetailCache(null, null, null, null, stockCache);
		CountDownLatch started = new CountDownLatch(THREADS);

		List<Future<GoodsStockSnapshot>> futures = fire(cache, "1", "100", started);
		assertTrue(started.await(5, TimeUnit.SECONDS));
		Thread.sleep(200);
		stockCache.release.countDown();

		for (Future<GoodsStockSnapshot> future : futures) {
			Exception e = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
			assertSame(stockCache.failure, e.getCause());
		}
		assertEquals(1, stockCache.loads.get());

		stockCache.failure = null;
		TenantContextHolder.setTenantId("1");
		assertEquals("100", cache.getStock("100").getSpuId());
	}

	@Test
	@DisplayName("拿不到租户：不单飞，直接读（缓存 condition 本来也不走缓存）")
	void noTenantGoesStraightThrough() {
		GatedStockCache stockCache = new GatedStockCache();
		stockCache.release.countDown();
		GoodsDetailCache cache = new GoodsDetailCache(null, null, null, null, stockCache);

		cache.getStock("100");
		cache.getStock("100");

		assertEquals(2, stockCache.loads.get());
	}

}
