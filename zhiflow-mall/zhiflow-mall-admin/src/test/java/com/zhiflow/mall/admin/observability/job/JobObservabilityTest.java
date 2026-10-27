package com.zhiflow.mall.admin.observability.job;

import com.zhiflow.common.lock.exception.DistributedLockException;
import com.zhiflow.mall.common.observability.JobFailedException;
import com.zhiflow.mall.common.observability.JobMetrics;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 定时任务切面的 outcome 映射。
 */
class JobObservabilityTest {

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
	void outcomeMapping() {
		assertThat(ScheduledJobMetricsAspect.outcomeOf(new DistributedLockException("获取分布式锁失败: lock:x")))
				.isEqualTo(JobMetrics.SKIPPED);
		assertThat(ScheduledJobMetricsAspect.outcomeOf(new JobFailedException("租户 2 失败")))
				.isEqualTo(JobMetrics.FAILURE);
		assertThat(ScheduledJobMetricsAspect.outcomeOf(new IllegalStateException("boom"))).isEqualTo(JobMetrics.ERROR);
	}

	@Test
	void aroundRecordsAndRethrowsUnchanged() throws Throwable {
		ScheduledJobMetricsAspect aspect = new ScheduledJobMetricsAspect(mock(ApplicationContext.class));
		ProceedingJoinPoint ok = joinPoint("runYesterday");
		when(ok.proceed()).thenReturn(null);
		aspect.around(ok);

		ProceedingJoinPoint failing = joinPoint("runYesterday");
		JobFailedException boom = new JobFailedException("N13 失败租户 [2]");
		when(failing.proceed()).thenThrow(boom);
		assertThatThrownBy(() -> aspect.around(failing)).isSameAs(boom);

		String job = "JobObservabilityTest#runYesterday";
		assertThat(registry.get(JobMetrics.M_RUNS).tags("job", job, "outcome", "success").counter().count()).isEqualTo(1.0);
		assertThat(registry.get(JobMetrics.M_RUNS).tags("job", job, "outcome", "failure").counter().count()).isEqualTo(1.0);
		assertThat(registry.get(JobMetrics.M_DURATION).tag("job", job).timer().count()).isEqualTo(2);
	}

	/**
	 * 任务方法体主动整轮跳过（业务开关暂停结算）：本轮记 skipped，不记 success，更不能记 error（JobFailed 会误报）；
	 * 标记只属于这一轮——下一轮正常返回仍记 success；抛异常时照常记 error 并清掉标记。
	 */
	@Test
	void markSkippedInBody_recordsSkipped_andDoesNotLeakIntoNextRun() throws Throwable {
		ScheduledJobMetricsAspect aspect = new ScheduledJobMetricsAspect(mock(ApplicationContext.class));
		String job = "JobObservabilityTest#settle";

		ProceedingJoinPoint pausedRun = joinPoint("settle");
		when(pausedRun.proceed()).thenAnswer(inv -> {
			JobMetrics.markSkipped();
			return null;
		});
		aspect.around(pausedRun);

		ProceedingJoinPoint normalRun = joinPoint("settle");
		when(normalRun.proceed()).thenReturn(null);
		aspect.around(normalRun);

		ProceedingJoinPoint failingAfterMark = joinPoint("settle");
		IllegalStateException boom = new IllegalStateException("boom");
		when(failingAfterMark.proceed()).thenAnswer(inv -> {
			JobMetrics.markSkipped();
			throw boom;
		});
		assertThatThrownBy(() -> aspect.around(failingAfterMark)).isSameAs(boom);
		assertThat(JobMetrics.consumeSkipped()).as("异常路径也要清掉标记").isFalse();

		assertThat(registry.get(JobMetrics.M_RUNS).tags("job", job, "outcome", "skipped").counter().count()).isEqualTo(1.0);
		assertThat(registry.get(JobMetrics.M_RUNS).tags("job", job, "outcome", "success").counter().count()).isEqualTo(1.0);
		assertThat(registry.get(JobMetrics.M_RUNS).tags("job", job, "outcome", "error").counter().count()).isEqualTo(1.0);
	}

	/** 同一调度线程上遗留的标记（例如某次没经切面的直调）不能把下一轮正常运行记成 skipped。 */
	@Test
	void staleMarkFromOutsideTheAspectIsClearedOnEntry() throws Throwable {
		ScheduledJobMetricsAspect aspect = new ScheduledJobMetricsAspect(mock(ApplicationContext.class));
		JobMetrics.markSkipped();

		ProceedingJoinPoint normalRun = joinPoint("stale");
		when(normalRun.proceed()).thenReturn(null);
		aspect.around(normalRun);

		String job = "JobObservabilityTest#stale";
		assertThat(registry.get(JobMetrics.M_RUNS).tags("job", job, "outcome", "success").counter().count()).isEqualTo(1.0);
		assertThat(registry.find(JobMetrics.M_RUNS).tags("job", job, "outcome", "skipped").counter()).isNull();
	}

	@SuppressWarnings("unchecked")
	private static ProceedingJoinPoint joinPoint(String method) {
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		Signature sig = mock(Signature.class);
		when(sig.getDeclaringType()).thenReturn((Class) JobObservabilityTest.class);
		when(sig.getName()).thenReturn(method);
		when(pjp.getSignature()).thenReturn(sig);
		return pjp;
	}
}
