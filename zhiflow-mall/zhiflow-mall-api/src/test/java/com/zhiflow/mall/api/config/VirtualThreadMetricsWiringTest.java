package com.zhiflow.mall.api.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.jvm.JvmMetricsAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 虚拟线程灰度的观测前提：mall-api 类路径上有 micrometer-java21，且开关
 * {@value VirtualThreadObservabilityConfig#ENABLED_PROPERTY} 打开 ⇒ Boot 注册虚拟线程指标。
 *
 * <p>守两件事：依赖被人当成没用的删掉（删了编译不报错，灰度看板和告警 {@code vt-alerts.yml} 会静默变成空集）；
 * 开关关（缺失 / false / 值写错）时不建 JFR 事件流，进程与加依赖前一致。
 */
class VirtualThreadMetricsWiringTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
				SimpleMetricsExportAutoConfiguration.class, JvmMetricsAutoConfiguration.class))
		.withUserConfiguration(VirtualThreadObservabilityConfig.class);

	@Test
	@DisplayName("开关打开：钉住时长与提交失败两个指标已注册")
	void virtualThreadMetersAreRegisteredWhenEnabled() {
		this.runner.withPropertyValues(VirtualThreadObservabilityConfig.ENABLED_PROPERTY + "=true").run(context -> {
			assertThat(context).hasNotFailed();
			MeterRegistry registry = context.getBean(MeterRegistry.class);
			assertThat(registry.find("jvm.threads.virtual.pinned").timer()).isNotNull();
			assertThat(registry.find("jvm.threads.virtual.submit.failed").counter()).isNotNull();
		});
	}

	@Test
	@DisplayName("开关缺失：不注册虚拟线程指标 Bean，也没有 jvm.threads.virtual.* 指标")
	void virtualThreadMetersAbsentWhenSwitchMissing() {
		this.runner.run(context -> assertOff(context));
	}

	@ParameterizedTest
	@ValueSource(strings = { "false", "ture", "开" })
	@DisplayName("开关为 false 或值写错：按关处理，应用照常启动")
	void virtualThreadMetersAbsentWhenSwitchOffOrMalformed(String value) {
		this.runner.withPropertyValues(VirtualThreadObservabilityConfig.ENABLED_PROPERTY + "=" + value)
			.run(context -> assertOff(context));
	}

	private static void assertOff(AssertableApplicationContext context) {
		assertThat(context).hasNotFailed();
		assertThat(context.getBeanNamesForType(MeterBinder.class))
			.noneMatch(name -> context.getBean(name).getClass().getName()
				.equals(VirtualThreadObservabilityConfig.VIRTUAL_THREAD_METRICS_CLASS));
		MeterRegistry registry = context.getBean(MeterRegistry.class);
		assertThat(registry.find("jvm.threads.virtual.pinned").timer()).isNull();
		assertThat(registry.find("jvm.threads.virtual.submit.failed").counter()).isNull();
	}

}
