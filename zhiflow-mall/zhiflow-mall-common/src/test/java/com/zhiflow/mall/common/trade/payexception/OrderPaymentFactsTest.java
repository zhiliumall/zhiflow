package com.zhiflow.mall.common.trade.payexception;

import com.zhiflow.mall.common.entity.OrderInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 差错池判据（v1.2 P7 / P9 / 3.3 ⑧⑨）：三条发现路径拼出同一个 dedupe_key；第二笔钱不能当重复回调吞掉。
 */
class OrderPaymentFactsTest {

	private static OrderInfo dbOrder(String paymentType, String txn) {
		OrderInfo o = new OrderInfo();
		o.setId("o1");
		o.setOrderNo("NO1");
		o.setTenantId("1");
		o.setPaymentType(paymentType);
		o.setTransactionId(txn);
		o.setPaymentPrice(new BigDecimal("287.00"));
		return o;
	}

	private static OrderInfo incoming(String paymentType, String txn, String amount, String currency) {
		OrderInfo o = new OrderInfo();
		o.setPaymentType(paymentType);
		o.setTransactionId(txn);
		o.setChannelPaidAmount(amount == null ? null : new BigDecimal(amount));
		o.setChannelPaidCurrency(currency);
		return o;
	}

	@Test
	@DisplayName("LATE_PAID：回调、MQ 入账、补单任务三条路径的 dedupe_key 一致（老渠道按订单 + 渠道）")
	void latePaidKeyIsStableAcrossPaths() {
		OrderInfo cancelled = dbOrder("4", null);
		String viaCallback = OrderPaymentFacts.latePaid(cancelled, incoming("4", "C1", "287", "CNY"), "NOTIFY_ORDER").dedupeKey();
		String viaJob = OrderPaymentFacts.latePaid(cancelled, incoming("4", "C1", null, null), "RECONCILE_JOB").dedupeKey();
		String probe = OrderPaymentFacts.latePaid(cancelled, cancelled, "").dedupeKey();

		assertEquals("LATE_PAID:order:o1:4", viaCallback);
		assertEquals(viaCallback, viaJob);
		assertEquals(viaCallback, probe, "补单任务的「是否已入池」判据必须与入池键一致，否则会反复向渠道查单");
	}

	@Test
	@DisplayName("LATE_PAID：新框架有支付单号时按支付单去重；实收金额优先取渠道回报，缺失才用应付兜底")
	void latePaidAmountAndPayOrderScope() {
		OrderInfo cancelled = dbOrder("8", null);
		OrderInfo in = incoming("8", "P1", "1540.10", "HKD");
		in.setChannelPayNo("PAY123");
		PayExceptionDraft d = OrderPaymentFacts.latePaid(cancelled, in, "NOTIFY_ORDER");

		assertEquals("LATE_PAID:po:PAY123", d.dedupeKey());
		assertEquals(154010L, d.amountMinor());
		assertEquals("HKD", d.currency());

		PayExceptionDraft fallback = OrderPaymentFacts.latePaid(cancelled, incoming("8", "P1", null, null), "X");
		assertEquals(28700L, fallback.amountMinor());
		assertEquals("CNY", fallback.currency());
	}

	@Test
	@DisplayName("P9：已付订单收到另一种支付方式的到账 → DUP_PAY（不是重复回调）")
	void differentPaymentTypeIsDupPay() {
		PayExceptionDraft d = OrderPaymentFacts.secondPaymentIfAny(dbOrder("4", "C1"), incoming("8", "P9", "1540", "HKD"));

		assertEquals(PayExceptionType.DUP_PAY, d.type());
		assertEquals("8", d.channel());
		assertEquals("P9", d.channelTxnId());
	}

	@Test
	@DisplayName("⑨：同一渠道、不同流水号 → DUP_CHARGE（渠道侧重复扣款，需核实，不自动退）")
	void sameTypeDifferentTxnIsDupCharge() {
		PayExceptionDraft d = OrderPaymentFacts.secondPaymentIfAny(dbOrder("4", "C1"), incoming("4", "C2", "287", "CNY"));

		assertEquals(PayExceptionType.DUP_CHARGE, d.type());
		assertEquals("DUP_CHARGE:txn:4:C2", d.dedupeKey());
	}

	@Test
	@DisplayName("对照组：同方式同流水 = 渠道重推，不入池；信息不足也不入池")
	void genuineRetryIsNotPooled() {
		assertNull(OrderPaymentFacts.secondPaymentIfAny(dbOrder("4", "C1"), incoming("4", "C1", "287", "CNY")));
		assertNull(OrderPaymentFacts.secondPaymentIfAny(dbOrder("4", "C1"), incoming(null, null, null, null)));
		assertNull(OrderPaymentFacts.secondPaymentIfAny(dbOrder("4", null), incoming("4", "C1", null, null)),
				"库里没流水号时无法判定，按重复处理（入账当时总会写流水号，这是历史单）");
	}

	@Test
	@DisplayName("AMOUNT_MISMATCH：有流水号按流水去重，金额按渠道回报记")
	void amountMismatch() {
		PayExceptionDraft d = OrderPaymentFacts.amountMismatch(dbOrder("4", null), "4", "C7",
				new BigDecimal("1.00"), null, "报文金额=1.00");

		assertEquals("AMOUNT_MISMATCH:txn:4:C7", d.dedupeKey());
		assertEquals(100L, d.amountMinor());
		assertEquals("CNY", d.currency());
	}

	@Test
	@DisplayName("元 → 分：两位小数四舍五入，null 进 null 出")
	void toMinor() {
		assertEquals(28700L, PayExceptionDraft.toMinor(new BigDecimal("287")));
		assertEquals(1L, PayExceptionDraft.toMinor(new BigDecimal("0.005")));
		assertNull(PayExceptionDraft.toMinor(null));
	}
}
