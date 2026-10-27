package com.zhiflow.mall.common.trade.statemachine.order;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.trade.statemachine.ConcurrentTransitionException;
import com.zhiflow.mall.common.trade.statemachine.Operator;
import com.zhiflow.mall.common.trade.statemachine.Transition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发（v1.0 阶段 1 验收：同一订单「取消」与「支付成功」同时到达，结果只有一个且流水完整）。
 * 第一道防线是锁，第二道是条件更新——两道分别单独验证。
 */
class OrderStateMachineConcurrencyTest {

	private static OrderInfo pendingPay() {
		OrderInfo o = new OrderInfo();
		o.setId("o1");
		o.setTenantId("1");
		o.setIsPay("0");
		o.setDeliveryWay("1");
		o.setPaymentPrice(new BigDecimal("10"));
		return o;
	}

	@RepeatedTest(50)
	@DisplayName("有锁：取消与支付成功同时到达 → 恰好一个 OK、另一个被拒，流水一行，状态与流水一致")
	void cancelVsPayWithLock() throws Exception {
		InMemoryOrderPort port = new InMemoryOrderPort();
		port.put(pendingPay());
		OrderStateMachine sm = new OrderStateMachine(port, InMemoryOrderPort.locks(), InMemoryOrderPort.directTx());
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Callable<Transition<OrderState>> cancel = () -> {
				go.await();
				return sm.fire("o1", OrderEvent.CANCEL, OrderTransitionContext.system("超时"));
			};
			Callable<Transition<OrderState>> pay = () -> {
				go.await();
				return sm.fire("o1", OrderEvent.PAY_SUCCESS,
						OrderTransitionContext.builder().operatorType(Operator.CHANNEL).build());
			};
			Future<Transition<OrderState>> a = pool.submit(cancel);
			Future<Transition<OrderState>> b = pool.submit(pay);
			go.countDown();
			List<Transition<OrderState>> results = List.of(a.get(), b.get());

			assertEquals(1, results.stream().filter(Transition::isOk).count(), results.toString());
			assertEquals(1, results.stream().filter(Transition::isRejected).count(), results.toString());
			assertEquals(1, port.logs.size());
			OrderState finalState = OrderState.of(port.rows.get("o1").getStatus(), port.rows.get("o1").getIsPay());
			assertEquals(port.logs.get(0).getToStatus(), finalState.name(), "终态与流水一致");
			assertTrue(finalState == OrderState.CANCELLED || finalState == OrderState.PENDING_SHIP);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("锁失效（第二道防线）：重读之后被并发改过 → 条件更新 0 行 → 抛并发异常、不写流水、不覆盖对方")
	void casIsTheSecondLine() {
		InMemoryOrderPort port = new InMemoryOrderPort();
		port.put(pendingPay());
		OrderStateMachine sm = new OrderStateMachine(port, InMemoryOrderPort.noLock(), InMemoryOrderPort.directTx());
		// 模拟：本线程重读完、还没 UPDATE 时，另一个写入者把订单取消了
		port.betweenLoadAndCas = () -> {
			port.betweenLoadAndCas = null;
			OrderInfo row = port.rows.get("o1");
			synchronized (row) {
				row.setStatus("5");
			}
		};

		assertThrows(ConcurrentTransitionException.class, () -> sm.fire("o1", OrderEvent.PAY_SUCCESS,
				OrderTransitionContext.builder().operatorType(Operator.CHANNEL).build()));

		assertEquals("5", port.rows.get("o1").getStatus(), "不能把对方的取消覆盖成已付");
		assertEquals("0", port.rows.get("o1").getIsPay());
		assertTrue(port.logs.isEmpty());
		assertTrue(port.events.isEmpty());
	}

	@Test
	@DisplayName("可重入：已持订单锁的调用链内部再 fire 不会自锁（notifyOrder 持 @DistributedLock 时调用状态机）")
	void reentrant() {
		InMemoryOrderPort port = new InMemoryOrderPort();
		port.put(pendingPay());
		var locks = InMemoryOrderPort.locks();
		OrderStateMachine sm = new OrderStateMachine(port, locks, InMemoryOrderPort.directTx());

		Transition<OrderState> t = locks.withLock(OrderStateMachine.lockKey("o1"), java.time.Duration.ofSeconds(1),
				() -> sm.fire("o1", OrderEvent.PAY_SUCCESS,
						OrderTransitionContext.builder().operatorType(Operator.CHANNEL).build()));

		assertTrue(t.isOk());
	}
}
