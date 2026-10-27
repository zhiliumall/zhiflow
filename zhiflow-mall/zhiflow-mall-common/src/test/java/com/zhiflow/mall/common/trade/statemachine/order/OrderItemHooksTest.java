package com.zhiflow.mall.common.trade.statemachine.order;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.trade.statemachine.Operator;
import com.zhiflow.mall.common.trade.statemachine.Transition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 订单跳转在同一事务里带动商品项——O5 取消把全部项置 CANCELLED；其余规则不碰商品项。 */
class OrderItemHooksTest {

	private final InMemoryOrderPort port = new InMemoryOrderPort();

	private final OrderStateMachine sm = new OrderStateMachine(port, InMemoryOrderPort.locks(), InMemoryOrderPort.directTx());

	private void order(String status, String isPay, String deliveryWay) {
		OrderInfo o = new OrderInfo();
		o.setId("o1");
		o.setTenantId("1");
		o.setStatus(status);
		o.setIsPay(isPay);
		o.setDeliveryWay(deliveryWay);
		o.setPaymentPrice(new BigDecimal("10"));
		port.put(o);
	}

	private static OrderTransitionContext by(Operator op) {
		return OrderTransitionContext.builder().operatorType(op).reason("测试").build();
	}

	@Test
	@DisplayName("O5 未付取消 → 商品项全部 CANCELLED")
	void o5CancelsItems() {
		order(null, "0", "1");
		assertTrue(sm.fire("o1", OrderEvent.CANCEL, by(Operator.USER)).isOk());
		assertEquals(List.of("o1"), port.itemsCancelled);
	}

	@Test
	@DisplayName("对照：O10 发货、O12 收货不碰商品项（商品项由发货自己的路径写）")
	void otherRulesLeaveItemsAlone() {
		order("1", "1", "1");
		assertTrue(sm.fire("o1", OrderEvent.SHIP, by(Operator.ADMIN)).isOk());
		assertTrue(sm.fire("o1", OrderEvent.RECEIVE, by(Operator.USER)).isOk());
		assertTrue(port.itemsCancelled.isEmpty());
	}

	@Test
	@DisplayName("对照：被拒 / 重复到达的取消不碰商品项")
	void rejectedOrNoopLeavesItemsAlone() {
		order("3", "1", "1");
		assertEquals(Transition.Outcome.REJECTED, sm.fire("o1", OrderEvent.CANCEL, by(Operator.USER)).outcome());
		order("5", "0", "1");
		assertEquals(Transition.Outcome.NOOP, sm.fire("o1", OrderEvent.CANCEL, by(Operator.USER)).outcome());
		assertTrue(port.itemsCancelled.isEmpty());
	}
}
