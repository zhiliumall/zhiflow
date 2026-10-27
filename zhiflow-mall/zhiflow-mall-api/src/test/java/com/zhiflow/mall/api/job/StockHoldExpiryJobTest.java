package com.zhiflow.mall.api.job;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.api.mapper.GoodsSkuMapper;
import com.zhiflow.mall.api.mapper.StockHoldMapper;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.api.trade.StockHoldService;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 过期兜底：待付款走 O5（原因记 EXPIRED_HOLD）、已取消漏放就放并回补、付过款的只确认、查不通下一轮。 */
class StockHoldExpiryJobTest {

	private final StockHoldMapper mapper = mock(StockHoldMapper.class);

	private final OrderInfoService orders = mock(OrderInfoService.class);

	private final GoodsSkuMapper skus = mock(GoodsSkuMapper.class);

	private final IdentifierGenerator ids = e -> 1L;

	private final StockHoldService holds = new StockHoldService(mapper, ids, new MockEnvironment());

	private final TransactionTemplate directTx = new TransactionTemplate() {
		@Override
		public <T> T execute(TransactionCallback<T> action) {
			return action.doInTransaction(null);
		}
	};

	private final StockHoldExpiryJob job = new StockHoldExpiryJob(mapper, holds, orders, skus, directTx, null, null);

	private OrderInfo order(String status, String isPay) {
		OrderInfo o = new OrderInfo();
		o.setId("o1");
		o.setStatus(status);
		o.setIsPay(isPay);
		return o;
	}

	@Test
	void pendingPayGoesThroughCancelAndTheReleaseIsTaggedExpired() {
		when(orders.getById("o1")).thenReturn(order(null, "0"), order("5", "0"));
		when(mapper.releaseHolds("o1", "EXPIRED_HOLD")).thenReturn(List.of(Map.of("skuId", "k1", "qty", 2)));
		// 真实的取消在状态机跳转成功后调 restoreOnCancel；这里用替身模拟这一段
		doAnswer(inv -> holds.restoreOnCancel("o1", skus::increaseStock)).when(orders).orderCancelV2("o1", OptSourceEnum.SYSTEM);

		assertEquals(StockHoldExpiryJob.Action.CANCELLED, job.handle("o1"));
		verify(mapper).releaseHolds("o1", "EXPIRED_HOLD");
		verify(skus).increaseStock("k1", 2);
	}

	@Test
	void unknownPaymentStatusWaitsForTheNextRound() {
		when(orders.getById("o1")).thenReturn(order(null, "0"));
		doThrow(new BizException("支付状态确认中，请稍后重试")).when(orders).orderCancelV2("o1", OptSourceEnum.SYSTEM);

		assertEquals(StockHoldExpiryJob.Action.WAITING, job.handle("o1"), "UNKNOWN 不是未付：不取消、不动库存");
		verify(skus, never()).increaseStock(anyString(), any());
	}

	@Test
	void cancelledOrderWithAForgottenHoldIsReleasedAndRestocked() {
		when(orders.getById("o1")).thenReturn(order("5", "0"));
		when(mapper.releaseHolds("o1", "EXPIRED_HOLD")).thenReturn(List.of(Map.of("skuId", "k9", "qty", 1)));

		assertEquals(StockHoldExpiryJob.Action.RELEASED, job.handle("o1"));
		verify(skus).increaseStock("k9", 1);
		verify(orders, never()).orderCancelV2(anyString(), any());
	}

	@Test
	void paidOrdersAreOnlyConfirmedAndIllegalOnesUntouched() {
		when(orders.getById("o1")).thenReturn(order("1", "1"));
		assertEquals(StockHoldExpiryJob.Action.CONFIRMED, job.handle("o1"));
		when(orders.getById("o1")).thenReturn(order("5", "1"));
		assertEquals(StockHoldExpiryJob.Action.SKIPPED, job.handle("o1"),
				"已付又标成已取消是表外组合：交人工处理，不动库存");
		verify(mapper, never()).releaseHolds(anyString(), anyString());
		verify(skus, never()).increaseStock(anyString(), any());
	}
}
