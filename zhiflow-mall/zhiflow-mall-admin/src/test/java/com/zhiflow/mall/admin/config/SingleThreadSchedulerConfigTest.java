package com.zhiflow.mall.admin.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打开虚拟线程开关后，mall-admin 的定时任务仍然在一条线程上排队串行。
 */
class SingleThreadSchedulerConfigTest {

	@Configuration(proxyBeanMethods = false)
	@EnableScheduling
	static class SchedulingOn {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
		.withUserConfiguration(SchedulingOn.class)
		.withPropertyValues("spring.threads.virtual.enabled=true");

	@Test
	@DisplayName("开关打开：调度器仍是单线程 ThreadPoolTaskScheduler，线程名前缀不变")
	void schedulerStaysSingleThreadedWithVirtualThreadsOn() {
		this.runner.withUserConfiguration(SingleThreadSchedulerConfig.class).run(context -> {
			assertThat(context).hasSingleBean(TaskScheduler.class);
			ThreadPoolTaskScheduler scheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
			// getPoolSize() 是「当前已建出的线程数」，没跑过任务时是 0；这里要的是配置上限
			assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
			assertThat(scheduler.getThreadNamePrefix()).isEqualTo("scheduling-");
		});
	}

	@Test
	@DisplayName("开关关：与 Boot 自动建的调度器等价（类型、线程数、线程名前缀），本配置不改变现状")
	void equivalentToBootDefaultWhenVirtualThreadsOff() {
		ApplicationContextRunner off = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
			.withUserConfiguration(SchedulingOn.class);
		off.run(bootDefault -> {
			ThreadPoolTaskScheduler expected = bootDefault.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
			off.withUserConfiguration(SingleThreadSchedulerConfig.class).run(context -> {
				assertThat(context).hasSingleBean(TaskScheduler.class);
				ThreadPoolTaskScheduler actual = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
				assertThat(actual.getScheduledThreadPoolExecutor().getCorePoolSize())
					.isEqualTo(expected.getScheduledThreadPoolExecutor().getCorePoolSize());
				assertThat(actual.getThreadNamePrefix()).isEqualTo(expected.getThreadNamePrefix());
			});
		});
	}

	@Test
	@DisplayName("对照：不加这个配置，开关一开 Boot 就换成每次触发开新线程的 SimpleAsyncTaskScheduler")
	void withoutPinBootSwitchesToSimpleAsyncScheduler() {
		this.runner.run(context -> assertThat(context.getBean(TaskScheduler.class))
			.isInstanceOf(SimpleAsyncTaskScheduler.class));
	}

}
