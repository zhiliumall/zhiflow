package com.zhiflow.mall.api.trade;

import com.zhiflow.mall.api.mapper.PayOrderMapper;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.trade.statemachine.order.OrderState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 支付结果查询：state 以支付单为准、UNKNOWN 不是失败、节奏服务端下发、只在待付款时探查。 */
class PayStatusServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 12, 0);

	private static PayOrder po(String status) {
		PayOrder p = new PayOrder();
		p.setPayNo("P-" + status);
		p.setStatus(status);
		p.setChannelCode("8");
		p.setCreateTime(NOW.minusMinutes(1));
		p.setFailReason("银行卡余额不足");
		return p;
	}

	@Test
	@DisplayName("穷举：订单状态 × 最近支付单 × 是否有已付支付单 × 最近探查结论")
	void deriveStateTruthTable() {
		for (OrderState os : OrderState.values()) {
			for (String latest : new String[]{null, "CREATED", "PAYING", "PAID", "FAILED", "CANCELLED", "REFUNDED"}) {
				for (boolean paid : new boolean[]{false, true}) {
					for (String probe : new String[]{null, "PAID", "FAILED", "PAYING", "UNKNOWN"}) {
						String s = PayStatusService.deriveState(os, latest == null ? null : po(latest),
								paid ? po("PAID") : null, probe);
						String expected = expected(os, latest, paid, probe);
						assertEquals(expected, s, os + "/" + latest + "/" + paid + "/" + probe);
					}
				}
			}
		}
	}

	private static String expected(OrderState os, String latest, boolean paid, String probe) {
		switch (os) {
			case PENDING_SHIP, PENDING_RECEIVE, COMPLETED:
				return "PAID";
			case CANCELLED:
				return "CLOSED";
			case PENDING_PAY:
				break;
			default:
				return "UNKNOWN";
		}
		if (paid) {
			return "PAID";
		}
		if (latest == null) {
			return "UNPAID";
		}
		return switch (latest) {
			case "CREATED", "PAYING" -> "UNKNOWN".equals(probe) ? "UNKNOWN" : "PAYING";
			case "FAILED" -> "FAILED";
			default -> "UNPAID";
		};
	}

	@Test
	@DisplayName("关键反例：支付单已 PAID 而订单还是待付款（入账事件未消费）→ PAID；查不通 → UNKNOWN 不是 FAILED")
	void keyCounterExamples() {
		assertEquals("PAID", PayStatusService.deriveState(OrderState.PENDING_PAY, po("PAYING"), po("PAID"), null));
		assertEquals("UNKNOWN", PayStatusService.deriveState(OrderState.PENDING_PAY, po("PAYING"), null, "UNKNOWN"));
		assertEquals("PAYING", PayStatusService.deriveState(OrderState.PENDING_PAY, po("PAYING"), null, "FAILED"),
				"探查说失败但支付单还没落 FAILED（P5 在后台跑）：先按支付中，不抢先判失败");
	}

	@Test
	@DisplayName("轮询节奏：前 10 秒 1000，之后 3000，60 秒后 0（停，显示确认中）；终态 0")
	void cadence() {
		assertEquals(1000, PayStatusService.nextCheckAfterMs("PAYING", null));
		assertEquals(1000, PayStatusService.nextCheckAfterMs("PAYING", 9_999L));
		assertEquals(3000, PayStatusService.nextCheckAfterMs("UNKNOWN", 10_000L));
		assertEquals(0, PayStatusService.nextCheckAfterMs("PAYING", 60_000L));
		assertEquals(0, PayStatusService.nextCheckAfterMs("PAID", 0L));
		assertEquals(0, PayStatusService.nextCheckAfterMs("FAILED", 0L));
		assertEquals(0, PayStatusService.nextCheckAfterMs("CLOSED", 0L));
	}

	@Test
	@DisplayName("重试方式：还有可用支付单 → 复用链接；否则新建；已付 / 已关闭 → 不可重试")
	void retry() {
		assertEquals("REUSE_LINK", PayStatusService.retry("PAYING", po("PAYING"), NOW));
		PayOrder expired = po("PAYING");
		expired.setExpireTime(LocalDateTime.now().minusMinutes(1));
		assertEquals("NEW_PAYMENT", PayStatusService.retry("PAYING", expired, NOW));
		assertEquals("NEW_PAYMENT", PayStatusService.retry("FAILED", po("FAILED"), NOW));
		assertEquals("NONE", PayStatusService.retry("PAID", po("PAID"), NOW));
		assertEquals("NONE", PayStatusService.retry("CLOSED", null, NOW));
	}

	@Test
	@DisplayName("剩余可付秒数：30 分钟；非待付款 null")
	void remain() {
		OrderInfo o = new OrderInfo();
		o.setCreateTime(NOW.minusMinutes(10));
		assertEquals(20 * 60L, PayStatusService.payRemainSeconds(o, OrderState.PENDING_PAY, NOW));
		assertNull(PayStatusService.payRemainSeconds(o, OrderState.PENDING_SHIP, NOW));
	}

	@Test
	@DisplayName("探查只在待付款且还没付时投；已付 / 已关闭不打渠道；失败原因只在 FAILED 时带")
	void probeOnlyWhenPending() {
		PayOrderMapper mapper = mock(PayOrderMapper.class);
		PayStatusProbe probe = mock(PayStatusProbe.class);
		PayStatusService svc = new PayStatusService(mapper, probe);
		OrderInfo o = new OrderInfo();
		o.setId("O1");
		o.setCreateTime(NOW.minusMinutes(1));
		o.setIsPay("0");
		when(mapper.selectList(any())).thenReturn(List.of(po("PAYING")));
		when(probe.submit(any(), any())).thenReturn(true);

		PayStatusService.PayStatus s = svc.query(o, true, 0L, NOW);
		assertEquals("PAYING", s.state());
		assertTrue(s.probeSubmitted());
		assertNull(s.failReason());
		assertEquals("CAN_PAY", s.actions().get(0).code());

		o.setStatus("1");
		o.setIsPay("1");
		PayStatusService.PayStatus paid = svc.query(o, true, 0L, NOW);
		assertEquals("PAID", paid.state());
		assertFalse(paid.probeSubmitted());
		verify(probe, org.mockito.Mockito.times(1)).submit(any(), any());

		o.setStatus(null);
		o.setIsPay("0");
		when(mapper.selectList(any())).thenReturn(List.of(po("FAILED")));
		PayStatusService.PayStatus failed = svc.query(o, true, 0L, NOW);
		assertEquals("FAILED", failed.state());
		assertEquals("银行卡余额不足", failed.failReason(), "只给渠道真实给出的原因");
		verify(probe, org.mockito.Mockito.times(1)).submit(any(), any());
		verify(probe, never()).submit(any(), org.mockito.ArgumentMatchers.argThat(p -> p != null && "FAILED".equals(p.getStatus())));
	}
}
