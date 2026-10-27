package com.zhiflow.mall.api.trade;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.zhiflow.mall.api.mapper.StockHoldMapper;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderItem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 库存预占：开关只管写不写 HOLD；取消只回补真的放出的行；没有 HOLD 行的老订单交回原逻辑。 */
class StockHoldServiceTest {

	private final StockHoldMapper mapper = mock(StockHoldMapper.class);

	private final AtomicLong ids = new AtomicLong();

	private final IdentifierGenerator idGen = e -> ids.incrementAndGet();

	private final MockEnvironment env = new MockEnvironment();

	private final StockHoldService service = new StockHoldService(mapper, idGen, env);

	private final List<String> restocked = new ArrayList<>();

	private static OrderItem item(String id, String sku, int qty) {
		OrderItem i = new OrderItem();
		i.setId(id);
		i.setSkuId(sku);
		i.setQuantity(qty);
		return i;
	}

	@Test
	void holdIsWrittenOnlyWhenEnabled_expiringFiveMinutesAfterTheOrderTimeout() {
		OrderInfo o = new OrderInfo();
		o.setId("o1");
		o.setTenantId("1");
		service.hold(o, List.of(item("i1", "k1", 2)), 30);
		verify(mapper, never()).insertHold(anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString());

		env.setProperty(StockHoldService.ENABLED_KEY, "true");
		service.hold(o, List.of(item("i1", "k1", 2), item("i2", null, 1), item("i3", "k3", 0)), 30);
		verify(mapper).insertHold(anyString(), eq("o1"), eq("i1"), eq("k1"), eq(2), eq(35L), eq("1"));
		verify(mapper, never()).insertHold(anyString(), anyString(), eq("i2"), any(), anyInt(), anyLong(), anyString());
		verify(mapper, never()).insertHold(anyString(), anyString(), eq("i3"), anyString(), anyInt(), anyLong(), anyString());
	}

	private static <T> T any() {
		return org.mockito.ArgumentMatchers.any();
	}

	@Test
	void cancelRestocksOnlyTheRowsReleasedNow() {
		when(mapper.releaseHolds("o1", "ORDER_CANCEL")).thenReturn(List.of(Map.of("skuId", "k1", "qty", 2)));

		assertTrue(service.restoreOnCancel("o1", (sku, qty) -> restocked.add(sku + "x" + qty)));
		assertEquals(List.of("k1x2"), restocked);
	}

	@Test
	void cancelRestocksReleasedRowsInSkuIdAscendingOrder_sameLockOrderAsPlacingAnOrder() {
		// UPDATE … RETURNING 的返回顺序不保证：这里故意给降序，回补必须按 skuId 升序（10-26）
		when(mapper.releaseHolds("o1", "ORDER_CANCEL")).thenReturn(List.of(Map.of("skuId", "k3", "qty", 1),
				Map.of("skuId", "k1", "qty", 2), Map.of("skuId", "k2", "qty", 3)));

		assertTrue(service.restoreOnCancel("o1", (sku, qty) -> restocked.add(sku + "x" + qty)));
		assertEquals(List.of("k1x2", "k2x3", "k3x1"), restocked);
	}

	@Test
	void secondCancelAfterTheHoldWasAlreadyReleasedRestocksNothing() {
		when(mapper.releaseHolds("o1", "ORDER_CANCEL")).thenReturn(List.of());
		when(mapper.countHolds("o1")).thenReturn(1L);

		assertTrue(service.restoreOnCancel("o1", (sku, qty) -> restocked.add(sku)), "有预占行：由预占决定，不回落老逻辑");
		assertTrue(restocked.isEmpty(), "并发的另一方已经放出并回补过，这里不能再补");
	}

	@Test
	void orderWithoutHoldRowsFallsBackToTheCallersLegacyRestock() {
		when(mapper.releaseHolds("o1", "ORDER_CANCEL")).thenReturn(List.of());
		when(mapper.countHolds("o1")).thenReturn(0L);

		assertFalse(service.restoreOnCancel("o1", (sku, qty) -> restocked.add(sku)));
		assertTrue(restocked.isEmpty(), "返回 false 由调用方按原逻辑逐项回补");
	}

	@Test
	void expiredContextTagsTheReleaseReason() {
		when(mapper.releaseHolds("o1", "EXPIRED_HOLD")).thenReturn(List.of(Map.of("skuId", "k1", "qty", 1)));

		boolean viaHold = service.asExpired(() -> service.restoreOnCancel("o1", (sku, qty) -> restocked.add(sku)));

		assertTrue(viaHold);
		assertEquals(List.of("k1"), restocked);
		when(mapper.releaseHolds("o2", "ORDER_CANCEL")).thenReturn(List.of());
		service.restoreOnCancel("o2", (sku, qty) -> { });
		verify(mapper).releaseHolds("o2", "ORDER_CANCEL");
	}
}
