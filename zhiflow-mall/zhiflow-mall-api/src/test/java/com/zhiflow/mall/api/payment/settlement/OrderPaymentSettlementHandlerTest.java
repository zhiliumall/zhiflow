package com.zhiflow.mall.api.payment.settlement;

import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.enums.PayBizTypeEnum;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单入账处理器：回调验签后同步调 notifyOrder（方案 N-1）。
 *
 * <p>不用 Mockito —— {@link OrderInfoService} 是接口，用 JDK 动态代理记录调用。
 */
class OrderPaymentSettlementHandlerTest {

	private final List<OrderInfo> notified = new ArrayList<>();

	private OrderInfoService orderServiceReturning(OrderInfo byId, OrderInfo byWrapper) {
		InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
			case "getById" -> byId;
			case "getOne" -> byWrapper;
			case "notifyOrder" -> {
				notified.add((OrderInfo) args[0]);
				yield null;
			}
			default -> null;
		};
		return (OrderInfoService) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[]{OrderInfoService.class}, h);
	}

	private static PayOrder paidPayOrder() {
		PayOrder p = new PayOrder();
		p.setPayNo("P123456789");
		p.setOrderNo("SO999");
		p.setBizType(PayBizTypeEnum.ORDER.getValue());
		p.setBizId("order-id-1");
		p.setChannelCode("1");
		p.setChannelTxnId("4200001234");
		p.setAmount(new BigDecimal("88.50"));
		p.setCurrCode("CNY");
		p.setPayTime(LocalDateTime.of(2026, 9, 7, 10, 11, 12));
		return p;
	}

	private static OrderInfo unpaidOrder() {
		OrderInfo o = new OrderInfo();
		o.setId("order-id-1");
		o.setOrderNo("SO999");
		o.setTenantId("1");
		o.setIsPay("0");
		return o;
	}

	@Test
	void bizTypeIsOrder() {
		assertEquals(PayBizTypeEnum.ORDER.getValue(),
				new OrderPaymentSettlementHandler(orderServiceReturning(null, null)).bizType());
	}

	@Test
	void paidOrderIsSettledWithPaymentFacts() {
		new OrderPaymentSettlementHandler(orderServiceReturning(unpaidOrder(), null)).onPaid(paidPayOrder());

		assertEquals(1, notified.size(), "应同步入账恰好一次");
		OrderInfo o = notified.get(0);
		assertEquals("4200001234", o.getTransactionId());
		assertEquals("1", o.getPaymentType());
		assertEquals("P123456789", o.getChannelPayNo());
		assertEquals(0, new BigDecimal("88.50").compareTo(o.getChannelPaidAmount()));
		assertEquals(LocalDateTime.of(2026, 9, 7, 10, 11, 12), o.getPaymentTime());
	}

	/**
	 * 订单已支付时照样交给订单侧：notifyOrder 判定「已付且流水不同 → 差错池 DUP_PAY」，钱不能被静默吞掉。
	 */
	@Test
	void alreadyPaidOrderIsStillHandedToOrderSide() {
		OrderInfo paid = unpaidOrder();
		paid.setIsPay("1");
		new OrderPaymentSettlementHandler(orderServiceReturning(paid, null)).onPaid(paidPayOrder());
		assertEquals(1, notified.size());
	}

	/** 按 bizId 找不到时回退按订单号找。 */
	@Test
	void fallsBackToOrderNo() {
		new OrderPaymentSettlementHandler(orderServiceReturning(null, unpaidOrder())).onPaid(paidPayOrder());
		assertEquals(1, notified.size());
	}

	/** 找不到订单时不能抛异常：重推并不能让订单凭空出现，只会耗尽重试窗口。 */
	@Test
	void missingOrderDoesNotThrow() {
		OrderPaymentSettlementHandler handler = new OrderPaymentSettlementHandler(orderServiceReturning(null, null));
		assertDoesNotThrow(() -> handler.onPaid(paidPayOrder()));
		assertTrue(notified.isEmpty());
	}
}
