package com.zhiflow.mall.common.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link OrderInfo#getStatusDesc()} 的分支覆盖：这个派生 getter 会被 Jackson 序列化进所有订单接口响应，是前端状态列的唯一来源。
 */
class OrderInfoStatusDescTest {

	private static OrderInfo order(String status, String isPay) {
		OrderInfo o = new OrderInfo();
		o.setDeliveryWay("1");
		o.setStatus(status);
		o.setIsPay(isPay);
		return o;
	}

	@Test
	@DisplayName("未支付且无状态 → 待付款")
	void unpaidWithoutStatus() {
		assertEquals("待付款", order(null, "0").getStatusDesc());
	}

	@Test
	@DisplayName("状态为空且已支付 → null（不臆造文案）")
	void paidWithoutStatus() {
		assertNull(order(null, "1").getStatusDesc());
	}

	@Test
	@DisplayName("各状态码走 OrderInfoEnum 通用文案")
	void statusCodes() {
		assertEquals("待发货", order("1", "1").getStatusDesc());
		assertEquals("待收货", order("2", "1").getStatusDesc());
		assertEquals("已完成", order("3", "1").getStatusDesc());
		assertEquals("已取消", order("5", "0").getStatusDesc());
	}

	@Test
	@DisplayName("表外状态码（退款中、待审核、待成团等已删功能留下的码）→ 空串")
	void unknownCodes() {
		for (String s : new String[]{"6", "7", "10"}) {
			assertEquals("", order(s, "1").getStatusDesc(), s);
		}
	}

	@Test
	@DisplayName("status 带空白字符时先 strip 再判定")
	void statusIsStripped() {
		assertEquals("待发货", order(" 1 ", "1").getStatusDesc());
		assertEquals("已完成", order(" 3 ", "1").getStatusDesc());
	}
}
