package com.zhiflow.mall.common.orderno;

import com.zhiflow.common.core.exception.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T3 · `order_info_order_no_idx` 冲突的有限重试。
 */
class OrderNoDuplicateRetryTest {

	private SimpleMeterRegistry registry;

	private OrderNoDuplicateRetry retry;

	private final AtomicReference<String> sinkReason = new AtomicReference<>();

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		sinkReason.set(null);
		retry = new OrderNoDuplicateRetry(new OrderNoMetrics(registry),
				(reason, detail, cause) -> sinkReason.set(reason));
	}

	private double counter(String name, String tagKey, String tagValue) {
		return registry.get(name).tag(tagKey, tagValue).counter().count();
	}

	/** PG 的真实报错文本形态。 */
	private static DuplicateKeyException orderNoConflict() {
		return new DuplicateKeyException(
				"### Error updating database.  Cause: org.postgresql.util.PSQLException: "
						+ "ERROR: duplicate key value violates unique constraint \"order_info_order_no_idx\"");
	}

	private static DuplicateKeyException otherConflict() {
		return new DuplicateKeyException(
				"ERROR: duplicate key value violates unique constraint \"order_item_idempotent_idx\"");
	}

	/** 撞 order_no → 重试一次成功。 */
	@Test
	void retriesOnceAndRecovers() throws Exception {
		AtomicInteger attempts = new AtomicInteger();

		String result = retry.execute(() -> {
			if (attempts.incrementAndGet() == 1) {
				throw orderNoConflict();
			}
			return "ok";
		});

		assertEquals("ok", result);
		assertEquals(2, attempts.get(), "应恰好执行两次：首次 + 重试一次");
		assertEquals(1.0, counter("orderno_dup_retry_total", "result", OrderNoMetrics.RESULT_RECOVERED));
		assertNull(sinkReason.get(), "重试成功不应记为故障");
	}

	/** 重试后仍冲突 → 明确失败（BizException），计数 + 落 Sink，且不再继续重试。 */
	@Test
	void failsExplicitlyWhenRetryAlsoConflicts() {
		AtomicInteger attempts = new AtomicInteger();

		BizException ex = assertThrows(BizException.class, () -> retry.execute(() -> {
			attempts.incrementAndGet();
			throw orderNoConflict();
		}));

		assertEquals(2, attempts.get(), "只重试一次——连撞两次不是巧合，继续重试没有意义");
		assertEquals("订单创建失败，请重试", ex.getMessage());
		assertEquals(1.0, counter("orderno_dup_retry_total", "result", OrderNoMetrics.RESULT_EXHAUSTED));
		assertEquals(1.0, counter("orderno_generate_fail_total", "reason", OrderNoMetrics.REASON_DUPLICATE));
		assertEquals(OrderNoMetrics.REASON_DUPLICATE, sinkReason.get());
	}

	/** ⚠️ 其他约束冲突必须**原样抛出**，不得被当成重号吞掉或重试。 */
	@Test
	void otherConstraintViolationsPropagateUntouched() {
		AtomicInteger attempts = new AtomicInteger();
		DuplicateKeyException original = otherConflict();

		DuplicateKeyException thrown = assertThrows(DuplicateKeyException.class, () -> retry.execute(() -> {
			attempts.incrementAndGet();
			throw original;
		}));

		assertSame(original, thrown, "非 order_no 约束必须原样抛出，不能包装或吞掉");
		assertEquals(1, attempts.get(), "非 order_no 约束不得重试");
		assertEquals(0.0, counter("orderno_dup_retry_total", "result", OrderNoMetrics.RESULT_RECOVERED));
		assertEquals(0.0, counter("orderno_dup_retry_total", "result", OrderNoMetrics.RESULT_EXHAUSTED));
		assertNull(sinkReason.get());
	}

	/** 约束名藏在 cause 链深处也要认出来（MyBatis 会层层包装）。 */
	@Test
	void detectsConstraintNameNestedInCauseChain() {
		DuplicateKeyException nested = new DuplicateKeyException("wrapper",
				new RuntimeException("ERROR: duplicate key value violates unique constraint \"order_info_order_no_idx\""));

		org.junit.jupiter.api.Assertions.assertTrue(OrderNoDuplicateRetry.isOrderNoConflict(nested));
		org.junit.jupiter.api.Assertions.assertFalse(OrderNoDuplicateRetry.isOrderNoConflict(otherConflict()));
	}

	/** 正常路径零开销：不抛异常时原样返回，不碰任何计数。 */
	@Test
	void passesThroughWhenNoConflict() throws Exception {
		assertEquals("done", retry.execute(() -> "done"));
		assertEquals(0.0, counter("orderno_dup_retry_total", "result", OrderNoMetrics.RESULT_RECOVERED));
	}
}
