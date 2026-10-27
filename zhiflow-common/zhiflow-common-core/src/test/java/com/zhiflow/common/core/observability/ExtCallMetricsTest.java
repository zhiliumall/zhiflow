package com.zhiflow.common.core.observability;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外部依赖统一出口：结论归类、异常原样透传、标签基数闸。
 */
class ExtCallMetricsTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	@BeforeEach
	void setUp() {
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void tearDown() {
		Metrics.removeRegistry(registry);
		registry.clear();
	}

	@Test
	void success_returnsResultUnchanged() {
		String r = ExtCallMetrics.record("t_dep", "query", () -> "ok");
		assertThat(r).isEqualTo("ok");
		assertThat(count("t_dep", "query", "success")).isEqualTo(1.0);
		assertThat(registry.get(ExtCallMetrics.M_DURATION).tags("dep", "t_dep", "op", "query").timer().count())
				.isEqualTo(1);
	}

	@Test
	void timeout_isClassifiedFromCauseChain_andOriginalExceptionRethrown() {
		IOException wrapped = new IOException("io", new SocketTimeoutException("read timed out"));
		assertThatThrownBy(() -> ExtCallMetrics.record("t_dep", "refund", () -> {
			throw wrapped;
		})).isSameAs(wrapped);
		assertThat(count("t_dep", "refund", "timeout")).isEqualTo(1.0);

		assertThat(ExtCallMetrics.classify(new HttpTimeoutException("x"))).isEqualTo(ExtCallMetrics.TIMEOUT);
		assertThat(ExtCallMetrics.classify(new IllegalStateException("HTTP 500"))).isEqualTo(ExtCallMetrics.ERROR);
	}

	@Test
	void checkedExceptionTypeIsPreserved() {
		// 编译期：E 被推断为 IOException，调用方原来的 catch (IOException) 不用改
		try {
			ExtCallMetrics.record("t_dep", "upload", () -> {
				throw new IOException("disk");
			});
		}
		catch (IOException e) {
			assertThat(e).hasMessage("disk");
		}
		assertThat(count("t_dep", "upload", "error")).isEqualTo(1.0);
	}

	@Test
	void resultAndErrorClassifiers_mapBizReject() {
		ExtCallMetrics.record("t_dep", "send", () -> "FAIL", r -> "OK".equals(r) ? ExtCallMetrics.SUCCESS
				: ExtCallMetrics.BIZ_REJECT, null);
		assertThat(count("t_dep", "send", "biz_reject")).isEqualTo(1.0);

		assertThatThrownBy(() -> ExtCallMetrics.record("t_dep", "create", () -> {
			throw new IllegalArgumentException("ORDERPAID");
		}, null, t -> ExtCallMetrics.BIZ_REJECT)).isInstanceOf(IllegalArgumentException.class);
		assertThat(count("t_dep", "create", "biz_reject")).isEqualTo(1.0);

		// 归类器自己坏了：退回默认归类，不影响调用
		ExtCallMetrics.record("t_dep", "broken", () -> "x", r -> {
			throw new IllegalStateException("classifier bug");
		}, null);
		assertThat(count("t_dep", "broken", "success")).isEqualTo(1.0);
	}

	@Test
	void httpStatusMapping() {
		assertThat(ExtCallMetrics.outcomeOfHttpStatus(200)).isEqualTo(ExtCallMetrics.SUCCESS);
		assertThat(ExtCallMetrics.outcomeOfHttpStatus(302)).isEqualTo(ExtCallMetrics.SUCCESS);
		assertThat(ExtCallMetrics.outcomeOfHttpStatus(404)).isEqualTo(ExtCallMetrics.BIZ_REJECT);
		assertThat(ExtCallMetrics.outcomeOfHttpStatus(503)).isEqualTo(ExtCallMetrics.ERROR);
	}

	@Test
	void opsAreBoundedPerDep_andIllegalNamesBecomeOther() {
		IntStream.range(0, 30).forEach(i -> ExtCallMetrics.recordOutcome("t_cap", "op" + i, ExtCallMetrics.SUCCESS, 1));
		ExtCallMetrics.recordOutcome("t_cap", "op_overflow", ExtCallMetrics.SUCCESS, 1);
		ExtCallMetrics.recordOutcome("t_cap", "/v1/pa/refunds?id=123", ExtCallMetrics.SUCCESS, 1);
		assertThat(count("t_cap", "other", "success")).isEqualTo(2.0);
		// 非法 outcome 一律记 error，不让任意字符串进标签
		ExtCallMetrics.recordOutcome("t_dep", "x", "weird", 1);
		assertThat(count("t_dep", "x", "error")).isEqualTo(1.0);
	}

	private double count(String dep, String op, String outcome) {
		return registry.get(ExtCallMetrics.M_CALLS).tags("dep", dep, "op", op, "outcome", outcome).counter().count();
	}
}
