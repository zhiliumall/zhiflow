package com.zhiflow.mall.common.trade.statemachine.order;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.trade.statemachine.Operator;
import com.zhiflow.mall.common.trade.statemachine.Transition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫反例表（每条规则的条件列至少一个不满足的构造，CSV 维护）+ 目标解析。
 */
class OrderTransitionGuardTest {

	private static Transition<OrderState> fire(String status, String isPay, String deliveryWay, OrderEvent event,
			OrderTransitionContext ctx) {
		InMemoryOrderPort port = new InMemoryOrderPort();
		OrderInfo o = new OrderInfo();
		o.setId("o1");
		o.setTenantId("1");
		o.setStatus(status);
		o.setIsPay(isPay);
		o.setDeliveryWay(deliveryWay);
		o.setPaymentPrice(new BigDecimal("10"));
		port.put(o);
		Transition<OrderState> t = new OrderStateMachine(port, InMemoryOrderPort.locks(), InMemoryOrderPort.directTx())
				.fire("o1", event, ctx);
		if (t.isRejected()) {
			assertEquals(status, port.rows.get("o1").getStatus(), "被拒后状态不得变化");
			assertTrue(port.logs.isEmpty(), "被拒后不得写流水");
		}
		return t;
	}

	private static String nullIfDash(String s) {
		return "-".equals(s) ? null : s;
	}

	@ParameterizedTest(name = "[{index}] {0}：{7}")
	@CsvFileSource(resources = "/statemachine/order-guard-counterexamples.csv", numLinesToSkip = 1, delimiter = '|')
	@DisplayName("守卫反例：条件不满足一律 REJECTED(GUARD)，状态、流水不变")
	void counterexamples(String ruleId, String status, String isPay, String deliveryWay, String event, String operator,
			String reason, String expectedMessage, String manualTarget) {
		OrderTransitionContext ctx = OrderTransitionContext.builder()
				.operatorType(Operator.valueOf(operator))
				.reason(nullIfDash(reason))
				.manualTarget("-".equals(manualTarget) ? null : OrderState.valueOf(manualTarget))
				.build();

		Transition<OrderState> t = fire(nullIfDash(status), isPay, deliveryWay, OrderEvent.valueOf(event), ctx);

		assertEquals(Transition.Outcome.REJECTED, t.outcome(), ruleId);
		assertEquals(Transition.RejectKind.GUARD, t.rejectKind(), ruleId);
		assertTrue(t.reason().contains(expectedMessage), ruleId + " 实际原因：" + t.reason());
	}

	@Test
	@DisplayName("14.8 门禁：跳转表里每条规则在反例表里至少有一个反例")
	void everyRuleHasACounterexample() {
		java.util.Set<String> rules = OrderTransitions.TABLE.rules().stream()
				.map(com.zhiflow.mall.common.trade.statemachine.Rule::id)
				.collect(java.util.stream.Collectors.toSet());
		java.util.Set<String> missing = new java.util.TreeSet<>(rules);
		missing.removeAll(com.zhiflow.mall.common.trade.statemachine.GuardCounterexamples
				.ruleIds("/statemachine/order-guard-counterexamples.csv"));
		assertTrue(missing.isEmpty(), "这些规则没有反例：" + missing);
	}

	private static OrderTransitionContext channel() {
		return OrderTransitionContext.builder().operatorType(Operator.CHANNEL).build();
	}

	@Test
	@DisplayName("O2 支付成功 → 待发货；status='0' 与空值同义，也是待付款")
	void payTarget() {
		assertEquals(OrderState.PENDING_SHIP, fire(null, "0", "1", OrderEvent.PAY_SUCCESS, channel()).to());
		assertEquals(OrderState.PENDING_SHIP, fire("0", "0", "1", OrderEvent.PAY_SUCCESS, channel()).to());
	}

	@Test
	@DisplayName("M1 人工处理：LEGACY_INVALID 按后台选的合法状态落库并留原因")
	void manualFix() {
		Transition<OrderState> t = fire("6", "1", "1", OrderEvent.MANUAL_FIX, OrderTransitionContext.builder()
				.operatorType(Operator.ADMIN).operatorId("admin1").reason("变更单 CR-1").manualTarget(OrderState.COMPLETED).build());
		assertEquals(Transition.Outcome.OK, t.outcome());
		assertEquals(OrderState.COMPLETED, t.to());
	}

	@Test
	@DisplayName("已付又标成已取消（'5','1'）是表外组合：只能人工处理，取消、发货一律 NO_RULE")
	void paidCancelledIsLegacyInvalid() {
		assertEquals(OrderState.LEGACY_INVALID, OrderState.of("5", "1"));
		assertEquals(Transition.RejectKind.NO_RULE,
				fire("5", "1", "1", OrderEvent.SHIP, OrderTransitionContext.system("x")).rejectKind());
	}

	@Test
	@DisplayName("已取消订单上迟到的支付成功不改订单状态 → REJECTED(NO_RULE)，由支付侧记差错")
	void latePaymentOnCancelledOrderIsRejected() {
		assertEquals(Transition.RejectKind.NO_RULE,
				fire("5", "0", "1", OrderEvent.PAY_SUCCESS, channel()).rejectKind());
	}
}
