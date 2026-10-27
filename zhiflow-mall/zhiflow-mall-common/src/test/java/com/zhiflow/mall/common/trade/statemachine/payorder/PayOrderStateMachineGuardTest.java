package com.zhiflow.mall.common.trade.statemachine.payorder;

import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.trade.statemachine.Operator;
import com.zhiflow.mall.common.trade.statemachine.Transition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付单状态机的守卫反例、重复回调与并发。
 */
class PayOrderStateMachineGuardTest {

	private final InMemoryPayOrderPort port = new InMemoryPayOrderPort();

	private final PayOrderStateMachine sm = new PayOrderStateMachine(port, InMemoryPayOrderPort.locks(),
			InMemoryPayOrderPort.directTx());

	private PayOrder put(String status) {
		PayOrder p = InMemoryPayOrderPort.payOrder("PN1", status, 10000);
		port.put(p);
		return p;
	}

	private static PayOrderTransitionContext confirm(String txn, Boolean otherPaid) {
		return PayOrderTransitionContext.builder().operatorType(Operator.CHANNEL).channelTxnId(txn)
				.otherPaidExists(otherPaid).callbackLogId("CB").build();
	}

	@ParameterizedTest(name = "[{index}] {0}：{5}")
	@CsvFileSource(resources = "/statemachine/payorder-guard-counterexamples.csv", numLinesToSkip = 1, delimiter = '|')
	@DisplayName("14.8 guard 反例表：条件不满足一律 REJECTED(GUARD)，状态、流水、事件不变")
	void counterexamples(String ruleId, String status, String event, String operator, String otherPaid,
			String expectedMessage) {
		put(status);
		PayOrderTransitionContext ctx = PayOrderTransitionContext.builder()
				.operatorType(Operator.valueOf(operator))
				.channelTxnId("T1")
				.otherPaidExists("-".equals(otherPaid) ? null : Boolean.valueOf(otherPaid))
				.reason("反例")
				.build();

		Transition<PayOrderState> t = sm.fire("PN1", PayOrderEvent.valueOf(event), ctx);

		assertEquals(Transition.Outcome.REJECTED, t.outcome(), ruleId);
		assertEquals(Transition.RejectKind.GUARD, t.rejectKind(), ruleId);
		assertTrue(t.reason().contains(expectedMessage), ruleId + " 实际原因：" + t.reason());
		assertEquals(status, port.rows.get("PN1").getStatus(), ruleId);
		assertTrue(port.logs.isEmpty() && port.events.isEmpty(), ruleId);
	}

	@Test
	@DisplayName("14.8 门禁：跳转表里每条规则在反例表里至少有一个反例")
	void everyRuleHasACounterexample() {
		java.util.Set<String> missing = new java.util.TreeSet<>(PayOrderTransitions.TABLE.rules().stream()
				.map(com.zhiflow.mall.common.trade.statemachine.Rule::id).toList());
		missing.removeAll(com.zhiflow.mall.common.trade.statemachine.GuardCounterexamples
				.ruleIds("/statemachine/payorder-guard-counterexamples.csv"));
		assertTrue(missing.isEmpty(), "这些规则没有反例：" + missing);
	}

	@Test
	@DisplayName("P4 反例：未判定「同业务单是否已有 PAID」→ 拒；已有 → 拒（应走 P9）；买家不能确认收款")
	void p4Guards() {
		put("PAYING");
		assertEquals(Transition.RejectKind.GUARD, sm.fire("PN1", PayOrderEvent.PAY_CONFIRMED, confirm("T1", null)).rejectKind());
		assertEquals(Transition.RejectKind.GUARD, sm.fire("PN1", PayOrderEvent.PAY_CONFIRMED, confirm("T1", true)).rejectKind());
		assertEquals(Transition.RejectKind.GUARD, sm.fire("PN1", PayOrderEvent.PAY_CONFIRMED,
				confirm("T1", false).toBuilder().operatorType(Operator.USER).build()).rejectKind());
		assertEquals("PAYING", port.rows.get("PN1").getStatus());
		assertTrue(port.logs.isEmpty());
	}

	@Test
	@DisplayName("P4 放行：记流水号、支付时间；P9 反例：同业务单没有别的 PAID → 拒")
	void p4OkAndP9Guard() {
		put("PAYING");
		assertEquals(Transition.RejectKind.GUARD,
				sm.fire("PN1", PayOrderEvent.DUPLICATE_PAY_CONFIRMED, confirm("T1", false)).rejectKind());
		Transition<PayOrderState> t = sm.fire("PN1", PayOrderEvent.PAY_CONFIRMED, confirm("T1", false));
		assertTrue(t.isOk());
		assertEquals("PAID", port.rows.get("PN1").getStatus());
		assertEquals("T1", port.rows.get("PN1").getChannelTxnId());
		assertTrue(port.rows.get("PN1").getPayTime() != null);
	}

	@Test
	@DisplayName("P7 / P9 标记：迟到到账 late_paid、重复付款 duplicate_paid")
	void lateAndDuplicateFlags() {
		put("CANCELLED");
		assertTrue(sm.fire("PN1", PayOrderEvent.LATE_PAY_CONFIRMED, confirm("T1", false)).isOk());
		assertEquals(Boolean.TRUE, port.rows.get("PN1").getLatePaid());

		PayOrder p2 = InMemoryPayOrderPort.payOrder("PN2", "PAYING", 10000);
		port.put(p2);
		assertTrue(sm.fire("PN2", PayOrderEvent.DUPLICATE_PAY_CONFIRMED, confirm("T2", true)).isOk());
		assertEquals(Boolean.TRUE, port.rows.get("PN2").getDuplicatePaid());
		assertEquals("DUPLICATE_PAY_CONFIRMED", port.logs.get(1).getEvent());
	}

	@Test
	@DisplayName("第⑨步：已付单收到同一流水号 → NOOP；不同流水号 → REJECTED(DUP_CHARGE)，状态不动")
	void repeatedConfirmation() {
		PayOrder p = put("PAID");
		p.setChannelTxnId("T1");
		assertTrue(sm.fire("PN1", PayOrderEvent.PAY_CONFIRMED, confirm("T1", false)).isNoop());
		assertTrue(sm.fire("PN1", PayOrderEvent.PAY_CONFIRMED, confirm(null, false)).isNoop());
		Transition<PayOrderState> dup = sm.fire("PN1", PayOrderEvent.PAY_CONFIRMED, confirm("T9", false));
		assertTrue(dup.isRejected());
		assertTrue(dup.reason().startsWith(PayOrderStateMachine.DUP_CHARGE));
		assertEquals("T1", port.rows.get("PN1").getChannelTxnId());
		assertTrue(port.logs.isEmpty());
	}

	@Test
	@DisplayName("P6：本地立即关，记是否已向渠道关单与关单时刻")
	void orderClosed() {
		put("PAYING");
		assertTrue(sm.fire("PN1", PayOrderEvent.ORDER_CLOSED,
				PayOrderTransitionContext.builder().operatorType(Operator.SYSTEM).channelClosed(false).build()).isOk());
		assertEquals(Boolean.FALSE, port.rows.get("PN1").getChannelClosed());
		assertTrue(port.rows.get("PN1").getClosedTime() != null);
	}

	@Test
	@DisplayName("并发：50 个线程同时用同一流水号确认收款 → 恰好 1 次放行、49 次 NOOP、1 行流水、1 条事件")
	void concurrentConfirmations() throws Exception {
		put("PAYING");
		ExecutorService pool = Executors.newFixedThreadPool(16);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Transition<PayOrderState>>> fs = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			fs.add(pool.submit(() -> {
				go.await();
				return sm.fire("PN1", PayOrderEvent.PAY_CONFIRMED, confirm("T1", false));
			}));
		}
		go.countDown();
		int ok = 0;
		int noop = 0;
		for (Future<Transition<PayOrderState>> f : fs) {
			Transition<PayOrderState> t = f.get(10, TimeUnit.SECONDS);
			ok += t.isOk() ? 1 : 0;
			noop += t.isNoop() ? 1 : 0;
		}
		pool.shutdown();
		assertEquals(1, ok);
		assertEquals(49, noop);
		assertEquals(1, port.logs.size());
		assertEquals(1, port.events.size());
	}
}
