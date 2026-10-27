package com.zhiflow.mall.api.service.impl;

import com.zhiflow.mall.common.dto.PlaceOrderDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 10-18 压测：每秒 1000 单时连接池耗尽，购物车下单入口 orderAddMap 原来没有事务超时
 * （经它进来时 orderAddOne 是自调用，orderAddOne 上的 timeout 不生效）。两处共用一个常量，防止以后分开漂移。
 */
class OrderTxTimeoutAnnotationTest {

	@Test
	@DisplayName("下单事务超时常量为 5 秒（仅事务部分：等连接最多 10s + 事务 5s）")
	void timeoutConstantIsFiveSeconds() {
		assertEquals(5, OrderInfoServiceImpl.ORDER_TX_TIMEOUT_SECONDS);
	}

	@Test
	@DisplayName("orderAddMap 带事务超时，值取常量，rollbackFor 仍是 Exception")
	void orderAddMapHasTimeout() throws Exception {
		assertTimeoutAndRollback(txOf(
				OrderInfoServiceImpl.class.getMethod("orderAddMap", HttpServletRequest.class, Map.class)));
	}

	@Test
	@DisplayName("orderAddOne（秒杀 MQ 建单经代理直接调）同样用这个常量")
	void orderAddOneHasSameTimeout() throws Exception {
		assertTimeoutAndRollback(txOf(
				OrderInfoServiceImpl.class.getMethod("orderAddOne", HttpServletRequest.class, PlaceOrderDTO.class)));
	}

	private static Transactional txOf(java.lang.reflect.Method m) {
		Transactional tx = m.getAnnotation(Transactional.class);
		assertNotNull(tx, m.getName() + " 必须有 @Transactional");
		return tx;
	}

	private static void assertTimeoutAndRollback(Transactional tx) {
		assertEquals(OrderInfoServiceImpl.ORDER_TX_TIMEOUT_SECONDS, tx.timeout());
		assertTrue(Arrays.asList(tx.rollbackFor()).contains(Exception.class), "rollbackFor 必须含 Exception（受检异常也回滚）");
	}

}
