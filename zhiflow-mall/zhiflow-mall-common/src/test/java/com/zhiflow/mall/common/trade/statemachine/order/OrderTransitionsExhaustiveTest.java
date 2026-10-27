package com.zhiflow.mall.common.trade.statemachine.order;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderStateLog;
import com.zhiflow.mall.common.trade.statemachine.Operator;
import com.zhiflow.mall.common.trade.statemachine.Rule;
import com.zhiflow.mall.common.trade.statemachine.Transition;
import com.zhiflow.mall.common.trade.statemachine.TransitionTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单状态机穷举测试：<b>跳转表就是用例的唯一来源</b>。
 *
 * <p>遍历 {@link OrderState} × {@link OrderEvent} 的每一对：
 * <ul>
 *   <li>表内 → 造一张处于 from 的单，fire，断言 to ∈ 规则声明的目标集、库里状态码正确、流水恰好一行、outbox 事件恰好一条；</li>
 *   <li>登记的幂等重复 → NOOP，单据、流水、事件全部不变；</li>
 *   <li>其余 → REJECTED(NO_RULE)，单据、流水、事件全部不变。</li>
 * </ul>
 * 另有：规则覆盖率 100%、「表与测试同源」元测试（往表里偷偷加一条「已取消 → 待发货」，生成器会多出一个用例并失败）。
 */
class OrderTransitionsExhaustiveTest {

	/** 每个状态的库内存储（LEGACY_INVALID 取三种典型非法组合）。 */
	static final Map<OrderState, List<String[]>> STORAGE = new EnumMap<>(OrderState.class);

	static {
		STORAGE.put(OrderState.PENDING_PAY, List.of(new String[]{null, "0"}, new String[]{"0", "0"}));
		STORAGE.put(OrderState.PENDING_SHIP, List.<String[]>of(new String[]{"1", "1"}));
		STORAGE.put(OrderState.PENDING_RECEIVE, List.<String[]>of(new String[]{"2", "1"}));
		STORAGE.put(OrderState.COMPLETED, List.<String[]>of(new String[]{"3", "1"}));
		STORAGE.put(OrderState.CANCELLED, List.<String[]>of(new String[]{"5", "0"}));
		STORAGE.put(OrderState.LEGACY_INVALID,
				List.of(new String[]{"6", "1"}, new String[]{null, "1"}, new String[]{"5", "1"}, new String[]{"7", "1"}));
	}

	static OrderInfo order(String id, String status, String isPay, OrderEvent event) {
		OrderInfo o = new OrderInfo();
		o.setId(id);
		o.setOrderNo("NO-" + id);
		o.setTenantId("1");
		o.setStatus(status);
		o.setIsPay(isPay);
		o.setDeliveryWay("1");
		o.setPaymentPrice(new BigDecimal("287.00"));
		return o;
	}

	/** 能满足规则守卫的上下文：优先后台操作 + 填原因。 */
	static OrderTransitionContext ctxFor(Rule<OrderState, OrderEvent, OrderInfo, OrderTransitionContext> rule) {
		Operator op = rule == null || rule.operators().contains(Operator.ADMIN) ? Operator.ADMIN
				: rule.operators().iterator().next();
		return OrderTransitionContext.builder().operatorType(op).operatorId("u1").reason("测试")
				.manualTarget(OrderState.PENDING_SHIP).build();
	}

	static List<DynamicTest> generate(TransitionTable<OrderState, OrderEvent, OrderInfo, OrderTransitionContext> expectations,
			TransitionTable<OrderState, OrderEvent, OrderInfo, OrderTransitionContext> implementation, Set<String> hitRules) {
		List<DynamicTest> tests = new ArrayList<>();
		int seq = 0;
		for (OrderState s : OrderState.values()) {
			for (String[] storage : STORAGE.get(s)) {
				for (OrderEvent e : OrderEvent.values()) {
					String id = "o" + (++seq);
					String name = s + "(" + storage[0] + "," + storage[1] + ") × " + e;
					tests.add(DynamicTest.dynamicTest(name, () -> {
						InMemoryOrderPort port = new InMemoryOrderPort();
						OrderInfo o = order(id, storage[0], storage[1], e);
						port.put(o);
						OrderStateMachine sm = new OrderStateMachine(port, InMemoryOrderPort.locks(),
								InMemoryOrderPort.directTx(), implementation);
						Rule<OrderState, OrderEvent, OrderInfo, OrderTransitionContext> rule = expectations.find(s, e);

						Transition<OrderState> t = sm.fire(id, e, ctxFor(rule));

						OrderInfo after = port.rows.get(id);
						if (rule != null) {
							assertEquals(Transition.Outcome.OK, t.outcome(), name + " 表内规则 " + rule.id() + " 应当放行：" + t.reason());
							assertTrue(rule.possibleTargets().contains(t.to()), name + " → " + t.to() + " 不在规则目标集");
							assertEquals(t.to().statusCode(), after.getStatus(), name + " 状态码");
							assertEquals(t.to().isPayCode(), after.getIsPay(), name + " is_pay");
							assertEquals(1, port.logs.size(), name + " 流水应恰好一行");
							OrderStateLog log = port.logs.get(0);
							assertEquals(s.name(), log.getFromStatus());
							assertEquals(t.to().name(), log.getToStatus());
							assertEquals(e.name(), log.getEvent());
							assertEquals(rule.id(), log.getRuleId());
							assertEquals(1, port.events.size(), name + " outbox 事件应恰好一条");
							assertEquals(log.getId(), port.events.get(0).eventId(), "事件 id = 流水 id");
							hitRules.add(rule.id());
						}
						else {
							Transition.Outcome expected = expectations.isIdempotentRepeat(s, e)
									? Transition.Outcome.NOOP : Transition.Outcome.REJECTED;
							assertEquals(expected, t.outcome(), name);
							if (expected == Transition.Outcome.REJECTED) {
								assertEquals(Transition.RejectKind.NO_RULE, t.rejectKind(), name);
							}
							assertEquals(storage[0], after.getStatus(), name + " 状态不得变化");
							assertEquals(storage[1], after.getIsPay(), name + " is_pay 不得变化");
							assertTrue(port.logs.isEmpty(), name + " 不得写流水");
							assertTrue(port.events.isEmpty(), name + " 不得发事件");
						}
					}));
				}
			}
		}
		return tests;
	}

	static final Set<String> HIT = new HashSet<>();

	@TestFactory
	@DisplayName("订单 9 状态 × 12 事件 穷举（含 LEGACY_INVALID 三种存储、待付款两种存储）")
	List<DynamicTest> exhaustive() {
		return generate(OrderTransitions.TABLE, OrderTransitions.TABLE, HIT);
	}

	@Test
	@DisplayName("规则覆盖率 100%：每条规则至少被一个用例命中")
	void everyRuleIsCovered() {
		Set<String> hit = new HashSet<>();
		for (DynamicTest t : generate(OrderTransitions.TABLE, OrderTransitions.TABLE, hit)) {
			try {
				t.getExecutable().execute();
			}
			catch (Throwable e) {
				throw new AssertionError(t.getDisplayName(), e);
			}
		}
		Set<String> all = OrderTransitions.TABLE.rules().stream().map(Rule::id).collect(Collectors.toSet());
		assertEquals(all, hit, "未被命中的规则");
	}

	/**
	 * 14.8 验收：「在跳转表里故意加一条『已取消 → 待发货』，穷举测试自动出现一个新用例并失败」——证明表与测试同源。
	 * 这里用一张多了该规则的表生成期望、用真实表做实现：生成器必须报告至少一个失败。
	 */
	@Test
	@DisplayName("表与测试同源：表里偷偷加一条「已取消 → 待发货」，生成器立刻报失败")
	void tableAndTestsShareOneSource() {
		TransitionTable<OrderState, OrderEvent, OrderInfo, OrderTransitionContext> tampered = tamperedTable();
		List<String> failures = new ArrayList<>();
		for (DynamicTest t : generate(tampered, OrderTransitions.TABLE, new HashSet<>())) {
			try {
				t.getExecutable().execute();
			}
			catch (Throwable e) {
				failures.add(t.getDisplayName());
			}
		}
		assertFalse(failures.isEmpty(), "改了表但测试没失败 = 表与测试不同源");
		assertTrue(failures.stream().anyMatch(n -> n.startsWith("CANCELLED") && n.endsWith("SHIP")), failures.toString());
	}

	/** 真实表 + 一条非法规则 CANCELLED --SHIP--> PENDING_RECEIVE（只在测试里构造）。 */
	static TransitionTable<OrderState, OrderEvent, OrderInfo, OrderTransitionContext> tamperedTable() {
		var b = TransitionTable.<OrderState, OrderEvent, OrderInfo, OrderTransitionContext>builder(OrderState.class, OrderEvent.class);
		for (Rule<OrderState, OrderEvent, OrderInfo, OrderTransitionContext> r : OrderTransitions.TABLE.rules()) {
			var rb = b.rule(r.id());
			OrderState[] from = r.from().toArray(new OrderState[0]);
			rb.from(from[0], java.util.Arrays.copyOfRange(from, 1, from.length)).on(r.event());
			OrderState[] targets = r.possibleTargets().toArray(new OrderState[0]);
			rb.to(r.target(), targets[0], java.util.Arrays.copyOfRange(targets, 1, targets.length)).when(r.guard());
			Operator[] ops = r.operators().toArray(new Operator[0]);
			rb.by(ops[0], java.util.Arrays.copyOfRange(ops, 1, ops.length)).add();
		}
		b.rule("EVIL").from(OrderState.CANCELLED).on(OrderEvent.SHIP).to(OrderState.PENDING_RECEIVE).add();
		for (OrderState s : OrderState.values()) {
			for (OrderEvent e : OrderEvent.values()) {
				if (OrderTransitions.TABLE.isIdempotentRepeat(s, e)) {
					b.idempotent(s, e);
				}
			}
		}
		return b.build();
	}
}
