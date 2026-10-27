package com.zhiflow.mall.common.trade.statemachine.order;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.trade.statemachine.order.OrderActionResolver.ActionView;
import com.zhiflow.mall.common.trade.statemachine.order.OrderActionResolver.Facts;
import com.zhiflow.mall.common.trade.statemachine.order.OrderActionResolver.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 每个订单状态一个 actions 快照；已取消订单后台看不到「发货」。 */
class OrderActionResolverTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 12, 0);

	private static OrderInfo order(String status, String isPay) {
		OrderInfo o = new OrderInfo();
		o.setStatus(status);
		o.setIsPay(isPay);
		o.setDeliveryWay("1");
		o.setPaymentTime(NOW.minusHours(30));
		return o;
	}

	/** 快照形式：code → enabled（被拒的带原因前缀 !）。 */
	private static Map<String, String> snap(List<ActionView> list) {
		return list.stream().collect(Collectors.toMap(ActionView::code,
				a -> a.enabled() ? "on" : "off:" + a.disabledReason(), (x, y) -> x, LinkedHashMap::new));
	}

	private static final Facts PAYABLE = new Facts(600L);

	@Test
	@DisplayName("买家侧：每个状态的操作快照")
	void userSnapshots() {
		assertEquals(Map.of("CAN_PAY", "on", "CAN_CANCEL", "on"),
				snap(OrderActionResolver.resolve(order(null, "0"), Side.USER, PAYABLE, NOW)));
		assertEquals(Map.of("CAN_PAY", "off:订单已超时，请重新下单", "CAN_CANCEL", "on"),
				snap(OrderActionResolver.resolve(order(null, "0"), Side.USER, new Facts(0L), NOW)));
		assertTrue(OrderActionResolver.resolve(order("1", "1"), Side.USER, Facts.NONE, NOW).isEmpty(), "待发货：买家无操作");
		assertEquals(Map.of("CAN_CONFIRM", "on"),
				snap(OrderActionResolver.resolve(order("2", "1"), Side.USER, Facts.NONE, NOW)));
		assertTrue(OrderActionResolver.resolve(order("3", "1"), Side.USER, Facts.NONE, NOW).isEmpty(), "已完成：无操作");
		assertEquals(Map.of("CAN_DELETE", "on"), snap(OrderActionResolver.resolve(order("5", "0"), Side.USER, Facts.NONE, NOW)));
	}

	@Test
	@DisplayName("后台侧：状态快照；已取消订单看不到「发货」；非法组合只剩人工处理")
	void adminSnapshots() {
		assertEquals(Map.of("ADMIN_CANCEL", "on"), snap(OrderActionResolver.resolve(order(null, "0"), Side.ADMIN, Facts.NONE, NOW)));
		assertEquals(Map.of("ADMIN_SHIP", "on"),
				snap(OrderActionResolver.resolve(order("1", "1"), Side.ADMIN, Facts.NONE, NOW)));
		assertEquals(Map.of("ADMIN_RECEIVE", "on"),
				snap(OrderActionResolver.resolve(order("2", "1"), Side.ADMIN, Facts.NONE, NOW)));
		assertTrue(OrderActionResolver.resolve(order("5", "0"), Side.ADMIN, Facts.NONE, NOW).isEmpty());
		assertFalse(snap(OrderActionResolver.resolve(order("5", "0"), Side.ADMIN, Facts.NONE, NOW)).containsKey("ADMIN_SHIP"));
		assertEquals(Map.of("ADMIN_MANUAL_FIX", "on"),
				snap(OrderActionResolver.resolve(order("9", "1"), Side.ADMIN, Facts.NONE, NOW)), "非法组合只剩人工处理");
		assertEquals(Map.of("ADMIN_MANUAL_FIX", "on"),
				snap(OrderActionResolver.resolve(order("7", "1"), Side.ADMIN, Facts.NONE, NOW)), "旧的待审核码也是非法组合");
	}
}
