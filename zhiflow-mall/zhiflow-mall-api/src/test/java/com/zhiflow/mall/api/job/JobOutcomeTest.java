package com.zhiflow.mall.api.job;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 支付状态机 v1.2 阶段 0 · 0.10：任一单笔 / 租户失败 → 本轮报失败 + 计数。 */
class JobOutcomeTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	@BeforeEach
	void bind() {
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void unbind() {
		Metrics.removeRegistry(registry);
	}

	@Test
	@DisplayName("零失败 → 成功，摘要原样，不计数")
	void noFailures() {
		JobResult r = JobOutcome.of("jobA", 0, 0, "扫描=3");

		assertTrue(r.success());
		assertEquals("扫描=3", r.message());
		assertNull(registry.find(JobOutcome.METRIC).tag("job", "jobA").counter());
	}

	@Test
	@DisplayName("单笔失败 → 报失败，消息带单笔 / 租户数，指标累加失败笔数")
	void itemFailures() {
		JobResult r = JobOutcome.of("jobB", 2, 1, "扫描=3");

		assertFalse(r.success());
		assertTrue(r.message().contains("单笔 2"));
		assertTrue(r.message().contains("租户 1"));
		assertTrue(r.message().endsWith("扫描=3"));
		assertEquals(3.0, registry.get(JobOutcome.METRIC).tag("job", "jobB").counter().count());
	}
}
