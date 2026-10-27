package com.zhiflow.mall.common.trade.statemachine.payorder;

import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.entity.PayOrderStateLog;
import com.zhiflow.mall.common.trade.statemachine.Operator;
import com.zhiflow.mall.common.trade.statemachine.Rule;
import com.zhiflow.mall.common.trade.statemachine.Transition;
import com.zhiflow.mall.common.trade.statemachine.TransitionTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付单状态机穷举测试：跳转表是用例的唯一来源。
 *
 * <p>遍历 {@link PayOrderState} × {@link PayOrderEvent}：表内 → 放行、状态码正确、流水一行、事件一条；
 * 登记的幂等重复 → NOOP；已付类 × 确认收款（流水号不同）→ REJECTED(DUP_CHARGE)；其余 → REJECTED(NO_RULE)。
 * 被拒 / NOOP 时单据、流水、事件全部不变。另有规则覆盖率 100% 与「表与测试同源」元测试。
 */
class PayOrderTransitionsExhaustiveTest {

	static final Map<PayOrderState, List<String>> STORAGE = new EnumMap<>(PayOrderState.class);

	static {
		for (PayOrderState s : PayOrderState.values()) {
			if (s != PayOrderState.LEGACY_INVALID) {
				STORAGE.put(s, List.of(s.name()));
			}
		}
		// REFUNDING 已不再使用；空状态与未知码同样落 LEGACY_INVALID
		STORAGE.put(PayOrderState.LEGACY_INVALID, java.util.Arrays.asList("REFUNDING", "BOGUS", null));
	}

	static final Set<PayOrderState> PAID_LIKE = EnumSet.of(PayOrderState.PAID);

	static final Set<PayOrderEvent> CONFIRMS = EnumSet.of(PayOrderEvent.PAY_CONFIRMED,
			PayOrderEvent.LATE_PAY_CONFIRMED, PayOrderEvent.DUPLICATE_PAY_CONFIRMED);

	static PayOrder row(String payNo, String status, PayOrderEvent e) {
		PayOrder p = InMemoryPayOrderPort.payOrder(payNo, status, 10000);
		if (PAID_LIKE.contains(PayOrderState.of(status))) {
			p.setChannelTxnId("TXN-OLD");
		}
		return p;
	}

	/** 满足规则守卫的上下文。 */
	static PayOrderTransitionContext ctxFor(Rule<PayOrderState, PayOrderEvent, PayOrder, PayOrderTransitionContext> rule,
			PayOrderEvent e) {
		Operator op = rule == null ? Operator.SYSTEM
				: rule.operators().contains(Operator.CHANNEL) ? Operator.CHANNEL
				: rule.operators().contains(Operator.SYSTEM) ? Operator.SYSTEM : rule.operators().iterator().next();
		return PayOrderTransitionContext.builder().operatorType(op).reason("测试").callbackLogId("CB1")
				.channelTxnId("TXN-NEW").otherPaidExists(e == PayOrderEvent.DUPLICATE_PAY_CONFIRMED)
				.failReason("渠道拒绝").channelClosed(false).build();
	}

	static List<DynamicTest> generate(TransitionTable<PayOrderState, PayOrderEvent, PayOrder, PayOrderTransitionContext> expectations,
			TransitionTable<PayOrderState, PayOrderEvent, PayOrder, PayOrderTransitionContext> implementation,
			Set<String> hitRules) {
		List<DynamicTest> tests = new ArrayList<>();
		int seq = 0;
		for (PayOrderState s : PayOrderState.values()) {
			for (String storage : STORAGE.get(s)) {
				for (PayOrderEvent e : PayOrderEvent.values()) {
					String payNo = "P" + (++seq);
					String name = s + "(" + storage + ") × " + e;
					tests.add(DynamicTest.dynamicTest(name, () -> {
						InMemoryPayOrderPort port = new InMemoryPayOrderPort();
						PayOrder p = row(payNo, storage, e);
						port.put(p);
						PayOrder before = InMemoryPayOrderPort.copy(p);
						PayOrderStateMachine sm = new PayOrderStateMachine(port, InMemoryPayOrderPort.locks(),
								InMemoryPayOrderPort.directTx(), implementation);
						Rule<PayOrderState, PayOrderEvent, PayOrder, PayOrderTransitionContext> rule = expectations.find(s, e);

						Transition<PayOrderState> t = sm.fire(payNo, e, ctxFor(rule, e));

						PayOrder after = port.rows.get(payNo);
						if (rule != null) {
							assertEquals(Transition.Outcome.OK, t.outcome(), name + " 表内规则 " + rule.id() + " 应当放行：" + t.reason());
							assertTrue(rule.possibleTargets().contains(t.to()), name + " → " + t.to() + " 不在规则目标集");
							assertEquals(t.to().code(), after.getStatus(), name + " 状态码");
							assertEquals(1, port.logs.size(), name + " 流水应恰好一行");
							PayOrderStateLog log = port.logs.get(0);
							assertEquals(s.name(), log.getFromStatus());
							assertEquals(t.to().name(), log.getToStatus());
							assertEquals(e.name(), log.getEvent());
							assertEquals(rule.id(), log.getRuleId());
							assertEquals("CB1", log.getCallbackLogId(), name + " 回调留痕 id 进流水");
							assertEquals(1, port.events.size(), name + " outbox 事件应恰好一条");
							assertEquals(log.getId(), port.events.get(0).eventId(), "事件 id = 流水 id");
							hitRules.add(rule.id());
						}
						else {
							if (PAID_LIKE.contains(s) && CONFIRMS.contains(e)) {
								assertEquals(Transition.Outcome.REJECTED, t.outcome(), name);
								assertTrue(t.reason().startsWith(PayOrderStateMachine.DUP_CHARGE), name + "：" + t.reason());
							}
							else {
								Transition.Outcome expected = expectations.isIdempotentRepeat(s, e)
										? Transition.Outcome.NOOP : Transition.Outcome.REJECTED;
								assertEquals(expected, t.outcome(), name);
								if (expected == Transition.Outcome.REJECTED) {
									assertEquals(Transition.RejectKind.NO_RULE, t.rejectKind(), name);
								}
							}
							assertEquals(before.getStatus(), after.getStatus(), name + " 状态不得变化");
							assertTrue(port.logs.isEmpty(), name + " 不得写流水");
							assertTrue(port.events.isEmpty(), name + " 不得发事件");
						}
					}));
				}
			}
		}
		return tests;
	}

	static final Set<String> HIT = java.util.Collections.synchronizedSet(new TreeSet<>());

	@TestFactory
	@DisplayName("支付单 状态 × 事件 穷举")
	List<DynamicTest> exhaustive() {
		return generate(PayOrderTransitions.TABLE, PayOrderTransitions.TABLE, HIT);
	}

	@Test
	@DisplayName("规模：6 状态（含 3 种非法存储）× 7 事件；规则 P2/P3/P4/P5/P6/P7/P9 全部被穷举命中")
	void coverage() {
		Set<String> hit = new TreeSet<>();
		List<DynamicTest> tests = generate(PayOrderTransitions.TABLE, PayOrderTransitions.TABLE, hit);
		assertEquals((PayOrderState.values().length - 1 + 3) * PayOrderEvent.values().length, tests.size());
		for (DynamicTest t : tests) {
			try {
				t.getExecutable().execute();
			}
			catch (Throwable ex) {
				throw new AssertionError(t.getDisplayName(), ex);
			}
		}
		Set<String> all = PayOrderTransitions.TABLE.rules().stream().map(Rule::id).collect(Collectors.toCollection(TreeSet::new));
		assertEquals(all, hit, "有规则没被穷举覆盖");
		assertEquals(Set.of("P2", "P3", "P4", "P5", "P6", "P7", "P9"), all, "表里规则与设计的 P2–P9（无 P8）一一对应");
	}

	@Test
	@DisplayName("元测试：往实现表里偷偷加一条「已付 → 已取消」，生成器必须报错（表与测试同源）")
	void tamperedTableIsCaught() {
		var tampered = TransitionTable.<PayOrderState, PayOrderEvent, PayOrder, PayOrderTransitionContext>builder(
				PayOrderState.class, PayOrderEvent.class);
		for (Rule<PayOrderState, PayOrderEvent, PayOrder, PayOrderTransitionContext> r : PayOrderTransitions.TABLE.rules()) {
			var rb = tampered.rule(r.id());
			PayOrderState[] from = r.from().toArray(new PayOrderState[0]);
			rb.from(from[0], java.util.Arrays.copyOfRange(from, 1, from.length)).on(r.event());
			PayOrderState[] targets = r.possibleTargets().toArray(new PayOrderState[0]);
			rb.to(r.target(), targets[0], java.util.Arrays.copyOfRange(targets, 1, targets.length)).when(r.guard());
			Operator[] ops = r.operators().toArray(new Operator[0]);
			rb.by(ops[0], java.util.Arrays.copyOfRange(ops, 1, ops.length)).add();
		}
		for (PayOrderState s : PayOrderState.values()) {
			for (PayOrderEvent e : PayOrderEvent.values()) {
				if (PayOrderTransitions.TABLE.isIdempotentRepeat(s, e)) {
					tampered.idempotent(s, e);
				}
			}
		}
		tampered.rule("EVIL").from(PayOrderState.PAID).on(PayOrderEvent.ORDER_CLOSED).to(PayOrderState.CANCELLED).add();

		List<DynamicTest> tests = generate(PayOrderTransitions.TABLE, tampered.build(), new TreeSet<>());
		List<String> failed = new ArrayList<>();
		for (DynamicTest t : tests) {
			try {
				t.getExecutable().execute();
			}
			catch (Throwable ex) {
				failed.add(t.getDisplayName());
			}
		}
		assertEquals(List.of("PAID(PAID) × ORDER_CLOSED"), failed);
	}
}
