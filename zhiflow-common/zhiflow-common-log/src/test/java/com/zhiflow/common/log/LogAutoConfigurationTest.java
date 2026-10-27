package com.zhiflow.common.log;

import com.zhiflow.api.upms.LogApi;
import com.zhiflow.common.log.aspect.SysLogAspect;
import com.zhiflow.common.log.config.BaseLogProperties;
import com.zhiflow.common.log.error.ErrorLogRecorder;
import com.zhiflow.common.log.event.SysLogListener;
import com.zhiflow.common.log.fallback.AuditFallbackWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link LogAutoConfiguration} 装配验证。
 *
 * <p><b>存在理由</b>：给一个服务加 {@code zhiflow-common-log} 依赖时，最大的风险是
 * 本自动配置会注册一批 bean（切面、监听器、兜底写入器、错误日志记录器、专用线程池），
 * 其中 {@link SysLogListener} 与 {@link ErrorLogRecorder} 都依赖 {@link LogApi}
 * （Spring 6 HTTP Interface）。若目标服务没有 {@code LogApi} bean，容器启动会直接失败。
 *
 * <p>mall-api 正是这种情况的验证对象：它此前<b>不依赖</b> common-log，因此 C 端既没有
 * {@code @SysLog} 切面也没有 {@code ErrorLogRecorder}。它的
 * {@code HttpClientApiConfiguration} 早已注册 {@code LogApi} 并注明
 * "required by LogAutoConfiguration"，属半成品。本测试确认：只要 {@code LogApi} 在，
 * 整套 bean 就能装配起来——用隔离的上下文验证，不必启动真实服务（启动完整服务有外部副作用，
 * 不如隔离上下文安全）。
 */
class LogAutoConfigurationTest {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(LogAutoConfiguration.class));

	@Test
	@DisplayName("提供 LogApi 后，全套日志 bean 均可装配")
	void allBeansAssembleWhenLogApiPresent() {
		runner.withUserConfiguration(LogApiProvider.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(SysLogAspect.class);
			assertThat(context).hasSingleBean(SysLogListener.class);
			assertThat(context).hasSingleBean(AuditFallbackWriter.class);
			assertThat(context).hasSingleBean(ErrorLogRecorder.class);
			assertThat(context).hasSingleBean(BaseLogProperties.class);
			assertThat(context).hasBean("sysLogExecutor");
		});
	}

	@Test
	@DisplayName("缺少 LogApi 时启动失败——这正是加依赖前必须确认 LogApi bean 存在的原因")
	void contextFailsWithoutLogApi() {
		runner.run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("sysLogExecutor 是有界池且拒绝策略可观测（不回灌业务线程）")
	void executorIsBoundedWithObservableRejection() {
		runner.withUserConfiguration(LogApiProvider.class).run(context -> {
			ThreadPoolTaskExecutor executor = context.getBean("sysLogExecutor", ThreadPoolTaskExecutor.class);
			assertThat(executor.getCorePoolSize()).isEqualTo(2);
			assertThat(executor.getMaxPoolSize()).isEqualTo(4);
			// 拒绝策略必须不是 CallerRunsPolicy —— 那会把 upms 的阻塞回灌到用户请求线程
			assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
					.isNotInstanceOf(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy.class);
		});
	}

	@Test
	@DisplayName("非 web 应用不装配（@ConditionalOnWebApplication）")
	void notAssembledOutsideWebApplication() {
		new org.springframework.boot.test.context.runner.ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(LogAutoConfiguration.class))
				.withUserConfiguration(LogApiProvider.class)
				.run(context -> assertThat(context).doesNotHaveBean(SysLogAspect.class));
	}

	@Configuration(proxyBeanMethods = false)
	static class LogApiProvider {

		@Bean
		LogApi logApi() {
			return mock(LogApi.class);
		}
	}
}
