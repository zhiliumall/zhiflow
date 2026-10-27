package com.zhiflow.mall.common.trade.payexception;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.zhiflow.mall.common.entity.PayException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 差错池入池（v1.2 第六章）：同一笔钱只入池一次、写库失败不抛而是返回 FAILED、独立事务、告警出口异常不回滚入池。
 */
class PayExceptionRecorderTest {

	/** 内存版 store：按 dedupe_key 模拟唯一索引。 */
	static final class FakeStore implements PayExceptionStore {
		final List<PayException> rows = new ArrayList<>();
		final Set<String> keys = new HashSet<>();
		RuntimeException fail;

		@Override
		public int insertIfAbsent(PayException row) {
			if (fail != null) {
				throw fail;
			}
			if (!keys.add(row.getDedupeKey())) {
				return 0;
			}
			rows.add(row);
			return 1;
		}
	}

	/** 记录传播行为的事务管理器。 */
	static final class RecordingTm implements PlatformTransactionManager {
		final List<Integer> propagations = new ArrayList<>();

		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			propagations.add(definition.getPropagationBehavior());
			return new SimpleTransactionStatus(true);
		}

		@Override
		public void commit(TransactionStatus status) {
		}

		@Override
		public void rollback(TransactionStatus status) {
		}
	}

	private final AtomicLong ids = new AtomicLong(100);

	private final IdentifierGenerator idGen = entity -> ids.incrementAndGet();

	private static PayExceptionDraft latePaid(String orderId) {
		return PayExceptionDraft.builder()
				.type(PayExceptionType.LATE_PAID)
				.dedupeScope(PayExceptionDraft.scopeOfOrder(orderId, "4"))
				.tenantId("1")
				.orderId(orderId)
				.orderNo("NO-" + orderId)
				.channel("4")
				.channelTxnId("C1")
				.amountMinor(28700L)
				.currency("CNY")
				.detail("test")
				.build();
	}

	@Test
	@DisplayName("新入池 → RECORDED，行字段齐全、状态 OPEN、主键由雪花给")
	void recordsNewRow() {
		FakeStore store = new FakeStore();
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		PayExceptionRecorder r = new PayExceptionRecorder(store, idGen, null, List.of(), meters);

		assertEquals(PayExceptionRecorder.Result.RECORDED, r.record(latePaid("o1")));

		PayException row = store.rows.get(0);
		assertEquals("101", row.getId());
		assertEquals("LATE_PAID", row.getType());
		assertEquals("OPEN", row.getStatus());
		assertEquals("LATE_PAID:order:o1:4", row.getDedupeKey());
		assertEquals(28700L, row.getAmountMinor());
		assertEquals("1", row.getTenantId());
		assertEquals(1.0, meters.get("pay_exception_recorded_total").tag("type", "LATE_PAID").counter().count());
	}

	@Test
	@DisplayName("同一笔钱第二次发现（回调、补单、对账先后到）→ ALREADY_RECORDED，只有一行、不重复告警")
	void sameMoneyOnlyOnce() {
		FakeStore store = new FakeStore();
		List<PayException> alerted = new ArrayList<>();
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		PayExceptionRecorder r = new PayExceptionRecorder(store, idGen, null, List.of(alerted::add), meters);

		r.record(latePaid("o1"));
		assertEquals(PayExceptionRecorder.Result.ALREADY_RECORDED, r.record(latePaid("o1")));

		assertEquals(1, store.rows.size());
		assertEquals(1, alerted.size());
		assertEquals(1.0, meters.get("pay_exception_recorded_total").tag("type", "LATE_PAID").counter().count());
	}

	@Test
	@DisplayName("写库异常 → 不抛、返回 FAILED、计失败指标（调用方据此让上游重试）")
	void storeFailureIsReportedNotThrown() {
		FakeStore store = new FakeStore();
		store.fail = new IllegalStateException("db down");
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		PayExceptionRecorder r = new PayExceptionRecorder(store, idGen, null, List.of(), meters);

		PayExceptionRecorder.Result result = r.record(latePaid("o1"));

		assertEquals(PayExceptionRecorder.Result.FAILED, result);
		assertTrue(!result.persisted());
		assertEquals(1.0, meters.get("pay_exception_record_failed_total").tag("type", "LATE_PAID").counter().count());
	}

	@Test
	@DisplayName("入池走独立事务（REQUIRES_NEW）：外层业务事务回滚，钱的事实不跟着消失")
	void usesRequiresNew() {
		RecordingTm tm = new RecordingTm();
		PayExceptionRecorder r = new PayExceptionRecorder(new FakeStore(), idGen, tm, List.of(), null);

		r.record(latePaid("o1"));

		assertEquals(List.of(TransactionDefinition.PROPAGATION_REQUIRES_NEW), tm.propagations);
	}

	@Test
	@DisplayName("告警出口抛异常 → 入池结果仍是 RECORDED，后续出口照常执行")
	void alertSinkFailureDoesNotUndoRecord() {
		FakeStore store = new FakeStore();
		List<PayException> second = new ArrayList<>();
		PayExceptionAlertSink boom = row -> {
			throw new RuntimeException("dingtalk down");
		};
		PayExceptionRecorder r = new PayExceptionRecorder(store, idGen, null, List.of(boom, second::add), null);

		assertEquals(PayExceptionRecorder.Result.RECORDED, r.record(latePaid("o1")));
		assertEquals(1, store.rows.size());
		assertEquals(1, second.size());
	}

	@Test
	@DisplayName("构造期预注册全部类型的指标：读数 0 = 没发生，查不到 = 埋点坏了")
	void metricsPreRegistered() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		new PayExceptionRecorder(new FakeStore(), idGen, null, List.of(), meters);

		for (PayExceptionType t : PayExceptionType.values()) {
			assertNotNull(meters.find("pay_exception_recorded_total").tag("type", t.name()).counter(), t.name());
			assertNotNull(meters.find("pay_exception_record_failed_total").tag("type", t.name()).counter(), t.name());
		}
	}

	@Test
	@DisplayName("没有判据（dedupeScope 为空）的草稿直接拒绝构造")
	void draftRequiresDedupeScope() {
		assertThrows(IllegalArgumentException.class, () -> PayExceptionDraft.builder()
				.type(PayExceptionType.LATE_PAID).dedupeScope(" ").build());
	}

	@Test
	@DisplayName("detail 超过列宽 1000 截断，dedupe_key 超过 160 截断")
	void truncation() {
		FakeStore store = new FakeStore();
		PayExceptionRecorder r = new PayExceptionRecorder(store, idGen, null, List.of(), null);

		r.record(PayExceptionDraft.builder().type(PayExceptionType.DUP_PAY)
				.dedupeScope("x".repeat(300)).detail("d".repeat(2000)).build());

		assertEquals(1000, store.rows.get(0).getDetail().length());
		assertEquals(160, store.rows.get(0).getDedupeKey().length());
	}

	/**
	 * 差错池最小版：只有「钱到了、订单收不了」的两类进表；其余两类不进表，
	 * 但打 ERROR 日志并计入同一个入池计数器（告警规则 PayExceptionRecorded 照常触发）——不能静默消失。
	 */
	@Test
	@DisplayName("最小版：LATE_PAID / DUP_PAY 进表；其余两类不进表、返回 LOGGED、照样计数告警、不发站内信")
	void onlyMoneyArrivedButOrderCannotTakeItIsPooled() {
		FakeStore store = new FakeStore();
		SimpleMeterRegistry reg = new SimpleMeterRegistry();
		List<String> alerted = new java.util.ArrayList<>();
		PayExceptionRecorder r = new PayExceptionRecorder(store, idGen, null, List.of(row -> alerted.add(row.getType())), reg);

		for (PayExceptionType t : PayExceptionType.values()) {
			PayExceptionRecorder.Result res = r.record(PayExceptionDraft.builder().type(t).dedupeScope("s-" + t)
					.orderNo("NO-1").amountMinor(100L).currency("CNY").detail("x").build());
			assertEquals(t.pooled() ? PayExceptionRecorder.Result.RECORDED : PayExceptionRecorder.Result.LOGGED, res, t.name());
			assertTrue(res.persisted(), t + "：调用方按「已有落点」处理（不让渠道无限重推）");
			assertEquals(1.0, reg.find("pay_exception_recorded_total").tag("type", t.name()).counter().count(),
					t + "：每一类都要计数，告警规则按它触发");
		}
		assertEquals(List.of("LATE_PAID", "DUP_PAY"), store.rows.stream().map(PayException::getType).toList());
		assertEquals(List.of("LATE_PAID", "DUP_PAY"), alerted, "站内信只发进表的两类");
	}

	private static PayExceptionDraft dupCharge(String id) {
		return PayExceptionDraft.builder().type(PayExceptionType.DUP_CHARGE).dedupeScope("txn:" + id)
				.tenantId("1").orderId("o-" + id).detail("x").build();
	}

	/**
	 * 2026-09-26 上线当晚：巡检首轮把 30 笔不进表的差错记成同一类型，
	 * 不去重的话每小时一轮、每轮一条严重级告警。同一笔窗口内只计一次，过了窗口再提醒。
	 */
	@Test
	@DisplayName("不进表的类型：同一笔窗口内重复发现只计一次，过了窗口再计；不同的单各计各的")
	void loggedTypesCountedOncePerWindow() {
		SimpleMeterRegistry reg = new SimpleMeterRegistry();
		AtomicLong now = new AtomicLong(1_000_000L);
		PayExceptionRecorder r = new PayExceptionRecorder(new FakeStore(), idGen, null, List.of(), reg,
				PayExceptionLoggedDedupe.inMemory(PayExceptionRecorder.LOGGED_DEDUPE_WINDOW, now::get));

		for (int round = 0; round < 3; round++) {
			assertEquals(PayExceptionRecorder.Result.LOGGED, r.record(dupCharge("a1")), "重复发现仍返回 LOGGED（调用方按已有落点处理）");
		}
		assertEquals(1.0, reg.find("pay_exception_recorded_total").tag("type", "DUP_CHARGE").counter().count());

		r.record(dupCharge("a2"));
		assertEquals(2.0, reg.find("pay_exception_recorded_total").tag("type", "DUP_CHARGE").counter().count(), "对照：另一笔照常计");

		now.addAndGet(PayExceptionRecorder.LOGGED_DEDUPE_WINDOW.toMillis() - 1);
		r.record(dupCharge("a1"));
		assertEquals(2.0, reg.find("pay_exception_recorded_total").tag("type", "DUP_CHARGE").counter().count(), "窗口内不再计");
		now.addAndGet(1);
		r.record(dupCharge("a1"));
		assertEquals(3.0, reg.find("pay_exception_recorded_total").tag("type", "DUP_CHARGE").counter().count(), "过了窗口再提醒一次");
	}

	@Test
	@DisplayName("去重本身出错（Redis 不通）→ 按首次上报：宁可重复告警，不可静默")
	void dedupeFailureStillReports() {
		SimpleMeterRegistry reg = new SimpleMeterRegistry();
		PayExceptionRecorder r = new PayExceptionRecorder(new FakeStore(), idGen, null, List.of(), reg, key -> {
			throw new IllegalStateException("redis down");
		});
		r.record(dupCharge("a1"));
		r.record(dupCharge("a1"));
		assertEquals(2.0, reg.find("pay_exception_recorded_total").tag("type", "DUP_CHARGE").counter().count());
	}

	@Test
	@DisplayName("进表的两类不走这条去重：仍靠 dedupe_key 唯一索引，与窗口无关")
	void pooledTypesIgnoreLoggedDedupe() {
		FakeStore store = new FakeStore();
		PayExceptionRecorder r = new PayExceptionRecorder(store, idGen, null, List.of(), new SimpleMeterRegistry(), key -> false);
		assertEquals(PayExceptionRecorder.Result.RECORDED, r.record(latePaid("o9")));
		assertEquals(1, store.rows.size());
	}
}
