package com.zhiflow.mall.common.orderno;

import com.zhiflow.common.core.exception.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3 · 时钟回拨两段行为的可见性。
 *
 * <p>回拨用可注入的时间源模拟——生产恒为 {@code System::currentTimeMillis}，
 * 抽出来只为单测能把时钟拨回去，正常路径逐字节不变。
 */
class OrderNoClockRollbackTest {

	private SimpleMeterRegistry registry;

	private OrderNoMetrics metrics;

	private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

	private OrderNoGenerator generatorWithRecordingSink(AtomicReference<String> sinkReason) {
		registry = new SimpleMeterRegistry();
		metrics = new OrderNoMetrics(registry);
		OrderNoFailureSink sink = (reason, detail, cause) -> sinkReason.set(reason);
		return new OrderNoGenerator(new FixedWorkerIdAssigner(0L, 0L), metrics, sink, clock::get);
	}

	private double counter(String name, String tagKey, String tagValue) {
		return registry.get(name).tag(tagKey, tagValue).counter().count();
	}

	/** 回拨 1s（Hutool 容忍区内）：WARN + 计数 +1，**发号仍然成功**。 */
	@Test
	void rollbackWithinToleranceCountsAndStillIssuesNumber() {
		OrderNoGenerator generator = generatorWithRecordingSink(new AtomicReference<>());

		generator.next();
		clock.addAndGet(-1000);

		String orderNo = generator.next();

		assertEquals(19, orderNo.length(), "容忍区内的回拨不应影响发号");
		assertTrue(orderNo.matches("\\d{19}"));
		assertEquals(1.0, counter("orderno_clock_rollback_total", "severity", OrderNoMetrics.SEVERITY_TOLERATED));
		assertEquals(0.0, counter("orderno_clock_rollback_total", "severity", OrderNoMetrics.SEVERITY_EXCEEDED));
	}

	/**
	 * 回拨 5s（超 Hutool 容忍区）：计数为 exceeded。
	 *
	 * <p>注意 Hutool 是拿它自己读到的 {@code System.currentTimeMillis()} 判断要不要抛，
	 * 与本类注入的假时钟是两个时间源。因此这里断言的是**本项目的观测与计数**这一层：
	 * 回拨被识别为超容忍区。真实环境下 Hutool 会抛 {@code IllegalStateException}，
	 * 由 {@link #wrapsHutoolIllegalStateInsteadOfLettingItEscape} 覆盖那条路径。
	 */
	@Test
	void rollbackBeyondToleranceIsCountedAsExceeded() {
		OrderNoGenerator generator = generatorWithRecordingSink(new AtomicReference<>());

		generator.next();
		clock.addAndGet(-5000);
		generator.next();

		assertEquals(1.0, counter("orderno_clock_rollback_total", "severity", OrderNoMetrics.SEVERITY_EXCEEDED));
		assertEquals(0.0, counter("orderno_clock_rollback_total", "severity", OrderNoMetrics.SEVERITY_TOLERATED));
	}

	/**
	 * Hutool 抛 {@code IllegalStateException} 时不得裸奔到用户：转成 {@link BizException}，
	 * 同时计 {@code generate_fail} 并落 Sink。
	 */
	@Test
	void wrapsHutoolIllegalStateInsteadOfLettingItEscape() {
		AtomicReference<String> sinkReason = new AtomicReference<>();
		registry = new SimpleMeterRegistry();
		metrics = new OrderNoMetrics(registry);
		OrderNoFailureSink sink = (reason, detail, cause) -> sinkReason.set(reason);

		// 机器位取一个本测试专用值，拿到独立的 Hutool Singleton 实例后把它的时钟基准推到未来，
		// 迫使下一次 nextId() 落进「回拨 > DEFAULT_TIME_OFFSET」分支
		OrderNoGenerator generator = new OrderNoGenerator(
				new FixedWorkerIdAssigner(11L, 5L), metrics, sink,
				() -> System.currentTimeMillis() - 10_000);

		cn.hutool.core.util.IdUtil.getSnowflake(11L, 5L).nextId();
		setHutoolLastTimestampToFuture();

		BizException ex = assertThrows(BizException.class, generator::next,
				"Hutool 的 IllegalStateException 必须被包成业务异常，不能裸奔到用户");

		assertTrue(ex.getMessage().contains("稍后重试"), "对用户的文案不应暴露内部异常，实际：" + ex.getMessage());
		assertEquals(1.0, counter("orderno_generate_fail_total", "reason", OrderNoMetrics.REASON_CLOCK_ROLLBACK));
		assertEquals(OrderNoMetrics.REASON_CLOCK_ROLLBACK, sinkReason.get(), "失败必须落 Sink，不能只留容器日志");
	}

	/** 把 Hutool Snowflake 内部的 lastTimestamp 推到 10 秒后，制造"超容忍区回拨"。 */
	private static void setHutoolLastTimestampToFuture() {
		try {
			cn.hutool.core.lang.Snowflake snowflake = cn.hutool.core.util.IdUtil.getSnowflake(11L, 5L);
			java.lang.reflect.Field field = cn.hutool.core.lang.Snowflake.class.getDeclaredField("lastTimestamp");
			field.setAccessible(true);
			field.setLong(snowflake, System.currentTimeMillis() + 10_000);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Hutool Snowflake.lastTimestamp 字段名变了，本测试需同步更新", e);
		}
	}

	/**
	 * 指标语义固化：`clock_rollback` 数的是**被回拨影响的发号次数**，不是回拨事件数。
	 *
	 * <p>水位取 max，所以一次回拨会让「追平水位之前的每一次发号」都命中。
	 * 读数远大于 1 是正常的，别当成 bug 去"修"。日志那边按水位去重（同一次回拨一条），
	 * 两者对不上是设计如此。
	 */
	@Test
	void counterMeasuresAffectedGenerationsNotRollbackEvents() {
		OrderNoGenerator generator = generatorWithRecordingSink(new AtomicReference<>());

		generator.next();
		clock.addAndGet(-1000);
		generator.next();
		generator.next();
		generator.next();

		assertEquals(3.0, counter("orderno_clock_rollback_total", "severity", OrderNoMetrics.SEVERITY_TOLERATED),
				"一次回拨、三次发号 → 计数应为 3（被影响的发号次数），不是 1");
	}

	/**
	 * ⚠️ Micrometer 首次命中才注册——「查不到」与「没工作」在读数上无法区分。
	 * 这里在构造期预注册全部 6 个组合，所以未触发任何事件时它们就该可查、且为 0。
	 */
	@Test
	void allCountersArePreRegisteredSoZeroMeansZeroNotMissing() {
		SimpleMeterRegistry freshRegistry = new SimpleMeterRegistry();
		new OrderNoMetrics(freshRegistry);

		for (String severity : new String[] { OrderNoMetrics.SEVERITY_TOLERATED, OrderNoMetrics.SEVERITY_EXCEEDED }) {
			assertNotNull(freshRegistry.get("orderno_clock_rollback_total").tag("severity", severity).counter());
			assertEquals(0.0, freshRegistry.get("orderno_clock_rollback_total").tag("severity", severity).counter().count());
		}
		for (String result : new String[] { OrderNoMetrics.RESULT_RECOVERED, OrderNoMetrics.RESULT_EXHAUSTED }) {
			assertNotNull(freshRegistry.get("orderno_dup_retry_total").tag("result", result).counter());
		}
		for (String reason : new String[] { OrderNoMetrics.REASON_CLOCK_ROLLBACK, OrderNoMetrics.REASON_DUPLICATE }) {
			assertNotNull(freshRegistry.get("orderno_generate_fail_total").tag("reason", reason).counter());
		}
	}

	/** 无 MeterRegistry（库模块被不带 actuator 的应用引用）时降级为不计数，不影响发号。 */
	@Test
	void degradesGracefullyWithoutMeterRegistry() {
		OrderNoGenerator generator = new OrderNoGenerator(
				new FixedWorkerIdAssigner(0L, 0L), new OrderNoMetrics(null), new NoOpOrderNoFailureSink());

		assertTrue(generator.next().matches("\\d{19}"));
	}
}
