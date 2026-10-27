package com.zhiflow.mall.api.job;

import com.zhiflow.mall.api.metrics.JobMetricsAspect;
import com.zhiflow.mall.common.observability.JobMetrics;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务指标切面的接线：job 包下 {@code @Scheduled} 方法所在的 bean 被代理后，调用照样走切面，
 * {@link JobResult#success()} = false 记 failure，指标的 job 标签 = 类名首字母小写；预注册把它登记为 0。
 *
 * <p>样本类放在 {@code com.zhiflow.mall.api.job} 包里：切面只认这个包（{@code within(...job..*)}）。
 */
class JobMetricsAspectWiringTest {

	@Configuration(proxyBeanMethods = false)
	@EnableAspectJAutoProxy(proxyTargetClass = true)
	static class Config {

		@Bean
		JobMetricsAspect jobMetricsAspect(org.springframework.context.ApplicationContext ctx) {
			return new JobMetricsAspect(ctx);
		}

		@Bean
		SampleJob sampleJob() {
			return new SampleJob();
		}
	}

	public static class SampleJob {

		/** 测试上下文没开 @EnableScheduling，这个 cron 不会真的触发，只由测试直接调用。 */
		@Scheduled(cron = "0 0 0 1 1 *")
		public JobResult execute() {
			return JobResult.failed("单笔失败 1");
		}
	}

	@Test
	@DisplayName("代理后的 @Scheduled 任务：调用走切面、失败记 failure、预注册为 0")
	void scheduledJobGoesThroughAspect() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Config.class)) {
			SampleJob job = ctx.getBean(SampleJob.class);
			assertThat(AopUtils.isCglibProxy(job)).as("对照：bean 确实被代理了").isTrue();

			ctx.getBean(JobMetricsAspect.class).preRegister();
			assertThat(registry.get(JobMetrics.M_RUNS).tags("job", "sampleJob", "outcome", "failure").counter().count())
					.as("预注册后查得到、读数为 0").isZero();

			JobResult r = job.execute();

			assertThat(r.success()).isFalse();
			assertThat(registry.get(JobMetrics.M_RUNS).tags("job", "sampleJob", "outcome", "failure").counter().count())
					.isEqualTo(1.0);
		}
		finally {
			Metrics.removeRegistry(registry);
		}
	}
}
