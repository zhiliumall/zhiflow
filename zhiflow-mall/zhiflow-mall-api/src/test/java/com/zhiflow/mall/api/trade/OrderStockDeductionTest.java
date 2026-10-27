package com.zhiflow.mall.api.trade;

import com.zhiflow.mall.api.mapper.GoodsSkuMapper;
import com.zhiflow.mall.common.entity.OrderItem;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.zhiflow.common.core.constant.CacheConstants;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.mock.env.MockEnvironment;

import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 10-18 B1：{@link OrderStockDeduction} 的开关读取、加锁顺序与锁异常翻译（不连库；真 PG 行为见 {@link OrderStockDeductionPgTest}）。
 */
class OrderStockDeductionTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private GoodsSkuMapper mapper;

	@BeforeEach
	void setUp() {
		Metrics.addRegistry(registry);
		mapper = mock(GoodsSkuMapper.class);
		when(mapper.decreaseStock(anyString(), anyInt())).thenReturn(1);
	}

	@AfterEach
	void tearDown() {
		removeBusyMeters();
		Metrics.removeRegistry(registry);
	}

	/**
	 * 计数器建在全局复合注册表上，测试完必须删掉：否则留在复合表里，后面的测试往全局表挂自己的注册表时
	 * 会被同步过去（PortalPasswordLoginMetricsTest 断言「自己的注册表里没有任何序列」就是这么被串挂的）。
	 */
	static void removeBusyMeters() {
		Metrics.globalRegistry.find(OrderStockDeduction.BUSY_METRIC).meters().forEach(Metrics.globalRegistry::remove);
	}

	@Test
	@DisplayName("开关读取：默认关；开了默认 2000ms；自定义值生效；非正数 / 配错格式回落 2000；DISABLED 恒关")
	void switchAndTimeoutReading() {
		assertFalse(new OrderStockDeduction(new MockEnvironment()).enabled());
		assertFalse(OrderStockDeduction.DISABLED.enabled());
		assertTrue(on(null).enabled());
		assertEquals(2000, on(null).lockTimeoutMs());
		assertEquals(1500, on("1500").lockTimeoutMs());
		assertEquals(2000, on("0").lockTimeoutMs());
		assertEquals(2000, on("-5").lockTimeoutMs());
		assertEquals(2000, on("2s").lockTimeoutMs());
	}

	@Test
	@DisplayName("配错不让下单失败（复核补）：开关值转不成布尔按关、不抛；时长超过 PG 上限（int 毫秒）回落 2000")
	void misconfigurationNeverFailsOrders() {
		OrderStockDeduction badSwitch = new OrderStockDeduction(
				new MockEnvironment().withProperty(OrderStockDeduction.ENABLED_KEY, "ture"));
		assertFalse(badSwitch.enabled());
		assertEquals(2000, on(String.valueOf(Integer.MAX_VALUE + 1L)).lockTimeoutMs());
		assertEquals(Integer.MAX_VALUE, on(String.valueOf(Integer.MAX_VALUE)).lockTimeoutMs());

		badSwitch.decrease(mapper, List.of(item("B", 1), item("A", 1)));
		InOrder order = inOrder(mapper);
		order.verify(mapper).decreaseStock("A", 1);
		order.verify(mapper).decreaseStock("B", 1);
		verify(mapper, never()).setLocalLockTimeout(anyString());
	}

	@Test
	@DisplayName("开关关：同样按 skuId 升序扣（10-26 起顺序与开关解耦），不发 set / reset")
	void offSortsBySkuIdWithoutExtraStatements() {
		OrderStockDeduction.DISABLED.decrease(mapper, List.of(item("s2", 2), item("s3", 3), item("s1", 1)));

		InOrder order = inOrder(mapper);
		order.verify(mapper).decreaseStock("s1", 1);
		order.verify(mapper).decreaseStock("s2", 2);
		order.verify(mapper).decreaseStock("s3", 3);
		verify(mapper, never()).setLocalLockTimeout(any());
		verify(mapper, never()).resetLocalLockTimeout();
	}

	@Test
	@DisplayName("开关开：先设等锁上限 → 按 skuId 升序扣 → 复位")
	void onSortsBySkuIdBetweenSetAndReset() {
		on("1500").decrease(mapper, List.of(item("s3", 3), item("s1", 1), item("s2", 2)));

		InOrder order = inOrder(mapper);
		order.verify(mapper).setLocalLockTimeout("1500ms");
		order.verify(mapper).decreaseStock("s1", 1);
		order.verify(mapper).decreaseStock("s2", 2);
		order.verify(mapper).decreaseStock("s3", 3);
		order.verify(mapper).resetLocalLockTimeout();
	}

	@Test
	@DisplayName("开关开：等锁超时（55P03）与死锁（40P01）都转成 70008，计数分 reason，不再复位（事务已 aborted）")
	void lockFailuresBecomeStockBusy() {
		CannotAcquireLockException timeout = new CannotAcquireLockException("canceling statement due to lock timeout");
		when(mapper.decreaseStock("s1", 1)).thenThrow(timeout);
		StockBusyException busy = assertThrows(StockBusyException.class,
				() -> on(null).decrease(mapper, List.of(item("s2", 1), item("s1", 1))));
		assertSame(timeout, busy.getCause());
		assertEquals("商品太火爆，请稍后再试", busy.getMessage());
		assertEquals(70008, StockBusyException.CODE.getCode());
		verify(mapper, never()).decreaseStock("s2", 1);
		verify(mapper, never()).resetLocalLockTimeout();

		// doThrow：同一参数上一次桩的是抛异常，when(...) 会先把它触发出来
		doThrow(new DeadlockLoserDataAccessException("deadlock detected", null)).when(mapper).decreaseStock("s1", 1);
		assertThrows(StockBusyException.class, () -> on(null).decrease(mapper, List.of(item("s1", 1))));

		assertEquals(1.0, registry.counter(OrderStockDeduction.BUSY_METRIC, "reason", "lock_timeout").count());
		assertEquals(1.0, registry.counter(OrderStockDeduction.BUSY_METRIC, "reason", "deadlock").count());
	}

	@Test
	@DisplayName("翻译器没认出来（UncategorizedSQLException）时按 SQLState 认：55P03 / 40P01 照样转 70008，其它 SQLState 不转")
	void sqlStateFallback() {
		doThrow(new UncategorizedSQLException("update", "UPDATE goods_sku ...",
				new SQLException("canceling statement due to lock timeout", "55P03")))
				.when(mapper).decreaseStock("s1", 1);
		assertThrows(StockBusyException.class, () -> on(null).decrease(mapper, List.of(item("s1", 1))));

		doThrow(new UncategorizedSQLException("update", "UPDATE goods_sku ...",
				new SQLException("deadlock detected", "40P01")))
				.when(mapper).decreaseStock("s1", 1);
		assertThrows(StockBusyException.class, () -> on(null).decrease(mapper, List.of(item("s1", 1))));

		UncategorizedSQLException other = new UncategorizedSQLException("update", "UPDATE goods_sku ...",
				new SQLException("division by zero", "22012"));
		doThrow(other).when(mapper).decreaseStock("s1", 1);
		assertSame(other, assertThrows(UncategorizedSQLException.class,
				() -> on(null).decrease(mapper, List.of(item("s1", 1)))));
	}

	@Test
	@DisplayName("开关开：非锁类异常（事务超时 57014）与库存不足照旧，不改写成 70008")
	void otherFailuresUnchanged() {
		QueryTimeoutException queryTimeout = new QueryTimeoutException("canceling statement due to statement timeout");
		when(mapper.decreaseStock("s1", 1)).thenThrow(queryTimeout);
		assertSame(queryTimeout, assertThrows(QueryTimeoutException.class,
				() -> on(null).decrease(mapper, List.of(item("s1", 1)))));

		when(mapper.decreaseStock("s2", 1)).thenReturn(null);
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> on(null).decrease(mapper, List.of(item("s2", 1))));
		assertEquals("请重新提交", ex.getMessage());
		assertFalse(ex instanceof StockBusyException);
	}

	@Test
	@DisplayName("开关关：锁异常原样抛出（与改动前一致，由全局处理器处理）")
	void offPassesLockFailuresThrough() {
		CannotAcquireLockException timeout = new CannotAcquireLockException("lock timeout");
		when(mapper.decreaseStock("s1", 1)).thenThrow(timeout);
		assertSame(timeout, assertThrows(CannotAcquireLockException.class,
				() -> OrderStockDeduction.DISABLED.decrease(mapper, List.of(item("s1", 1)))));
	}

	@Test
	@DisplayName("多店铺处理顺序：开关开 / 关都按店铺 id 升序（10-26 起与开关解耦）；单店铺原样返回同一个列表")
	void shopLockOrder() {
		List<Map.Entry<String, String>> shops = List.of(entry("shop-b"), entry("shop-a"), entry("shop-c"));
		assertEquals(List.of("shop-a", "shop-b", "shop-c"),
				OrderStockDeduction.DISABLED.lockOrder(shops).stream().map(Map.Entry::getKey).toList());
		assertEquals(List.of("shop-a", "shop-b", "shop-c"),
				on(null).lockOrder(shops).stream().map(Map.Entry::getKey).toList());
		List<Map.Entry<String, String>> single = List.of(entry("shop-b"));
		assertSame(single, OrderStockDeduction.DISABLED.lockOrder(single));
	}

	// ---------------------------------------------------------------- 售罄 / 低库存提交后清库存缓存（2026-10-23）

	@Test
	@DisplayName("阈值读取：默认 5；自定义生效；配错格式回落 5；DISABLED 恒关（-1）")
	void lowStockThresholdReading() {
		assertEquals(5, new OrderStockDeduction(new MockEnvironment()).lowStockThreshold());
		assertEquals(0, threshold("0").lowStockThreshold());
		assertEquals(-1, threshold("-1").lowStockThreshold());
		assertEquals(5, threshold("five").lowStockThreshold());
		assertEquals(-1, OrderStockDeduction.DISABLED.lowStockThreshold());
	}

	@Test
	@DisplayName("扣后剩余 ≤ 5 的商品提交后清库存缓存（去重）；> 5 的不清；提交前一个都不清")
	void lowStockEvictsAfterCommitOnly() {
		Cache cache = mock(Cache.class);
		CacheManager cacheManager = cacheManager(cache);
		when(mapper.decreaseStock("s1", 1)).thenReturn(6);
		when(mapper.decreaseStock("s2", 1)).thenReturn(5);
		when(mapper.decreaseStock("s3", 2)).thenReturn(0);
		when(mapper.decreaseStock("s4", 1)).thenReturn(3);

		List<TransactionSynchronization> hooks = inTransaction(() -> new OrderStockDeduction(new MockEnvironment(), cacheManager)
				.decrease(mapper, List.of(item("s1", 1, "p1"), item("s2", 1, "p2"), item("s3", 2, "p3"), item("s4", 1, "p2"))));

		verify(cache, never()).evict(any());
		hooks.forEach(TransactionSynchronization::afterCommit);
		verify(cache).evict("p2");
		verify(cache).evict("p3");
		verify(cache, never()).evict("p1");
		verify(cache, times(2)).evict(any());
	}

	@Test
	@DisplayName("开关开（排序扣）同样清；阈值 0 只在售罄时清；负数关")
	void thresholdZeroAndNegative() {
		Cache cache = mock(Cache.class);
		CacheManager cacheManager = cacheManager(cache);
		when(mapper.decreaseStock("s1", 1)).thenReturn(1);
		when(mapper.decreaseStock("s2", 1)).thenReturn(0);
		MockEnvironment env = new MockEnvironment().withProperty(OrderStockDeduction.ENABLED_KEY, "true")
			.withProperty(OrderStockDeduction.LOW_STOCK_THRESHOLD_KEY, "0");

		inTransaction(() -> new OrderStockDeduction(env, cacheManager)
				.decrease(mapper, List.of(item("s1", 1, "p1"), item("s2", 1, "p2"))))
			.forEach(TransactionSynchronization::afterCommit);
		verify(cache).evict("p2");
		verify(cache, never()).evict("p1");

		env.setProperty(OrderStockDeduction.LOW_STOCK_THRESHOLD_KEY, "-1");
		assertTrue(inTransaction(() -> new OrderStockDeduction(env, cacheManager)
				.decrease(mapper, List.of(item("s2", 1, "p2")))).isEmpty());
	}

	@Test
	@DisplayName("回滚不清：扣到一半库存不足整单失败，前面低库存的商品也不登记")
	void rollbackDoesNotEvict() {
		Cache cache = mock(Cache.class);
		when(mapper.decreaseStock("s1", 1)).thenReturn(0);
		when(mapper.decreaseStock("s2", 1)).thenReturn(null);

		List<TransactionSynchronization> hooks = new java.util.ArrayList<>();
		assertThrows(RuntimeException.class, () -> hooks.addAll(inTransaction(
				() -> new OrderStockDeduction(new MockEnvironment(), cacheManager(cache))
					.decrease(mapper, List.of(item("s1", 1, "p1"), item("s2", 1, "p2"))))));
		assertTrue(hooks.isEmpty());
		verify(cache, never()).evict(any());
	}

	@Test
	@DisplayName("清缓存失败不影响下单：登记时解析缓存抛错、提交后 evict 抛错都只记日志")
	void evictionFailuresNeverFailOrders() {
		when(mapper.decreaseStock("s1", 1)).thenReturn(0);
		CacheManager broken = mock(CacheManager.class);
		when(broken.getCache(CacheConstants.MALL_GOODS_STOCK_CACHE)).thenThrow(new IllegalStateException("redis down"));
		assertTrue(inTransaction(() -> new OrderStockDeduction(new MockEnvironment(), broken)
				.decrease(mapper, List.of(item("s1", 1, "p1")))).isEmpty());

		Cache cache = mock(Cache.class);
		doThrow(new IllegalStateException("redis down")).when(cache).evict("p1");
		List<TransactionSynchronization> hooks = inTransaction(() -> new OrderStockDeduction(new MockEnvironment(),
				cacheManager(cache)).decrease(mapper, List.of(item("s1", 1, "p1"))));
		hooks.forEach(TransactionSynchronization::afterCommit);
		verify(cache).evict("p1");
	}

	private static CacheManager cacheManager(Cache cache) {
		CacheManager cacheManager = mock(CacheManager.class);
		when(cacheManager.getCache(CacheConstants.MALL_GOODS_STOCK_CACHE)).thenReturn(cache);
		return cacheManager;
	}

	/** 在事务同步激活的状态下跑，返回登记的提交后钩子（不触发）。 */
	private static List<TransactionSynchronization> inTransaction(Runnable body) {
		TransactionSynchronizationManager.initSynchronization();
		try {
			body.run();
			return List.copyOf(TransactionSynchronizationManager.getSynchronizations());
		}
		finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	private static OrderStockDeduction threshold(String value) {
		return new OrderStockDeduction(
				new MockEnvironment().withProperty(OrderStockDeduction.LOW_STOCK_THRESHOLD_KEY, value));
	}

	private static OrderItem item(String skuId, int quantity, String spuId) {
		OrderItem item = item(skuId, quantity);
		item.setSpuId(spuId);
		return item;
	}

	private static OrderStockDeduction on(String ms) {
		MockEnvironment env = new MockEnvironment().withProperty(OrderStockDeduction.ENABLED_KEY, "true");
		if (ms != null) {
			env.setProperty(OrderStockDeduction.TIMEOUT_MS_KEY, ms);
		}
		return new OrderStockDeduction(env);
	}

	private static Map.Entry<String, String> entry(String shopId) {
		return new AbstractMap.SimpleEntry<>(shopId, shopId + "-dto");
	}

	private static OrderItem item(String skuId, int quantity) {
		OrderItem item = new OrderItem();
		item.setSkuId(skuId);
		item.setQuantity(quantity);
		return item;
	}

}
