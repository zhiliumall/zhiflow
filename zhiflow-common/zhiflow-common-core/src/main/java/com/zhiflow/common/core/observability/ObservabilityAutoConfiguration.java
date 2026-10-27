package com.zhiflow.common.core.observability;

import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 所有服务（含网关）共有的可观测装配。
 *
 * <p>{@link AppInfoMetrics} 以 {@link MeterBinder} 注册：Boot 会把全部 MeterBinder 绑到 registry 上，
 * 不需要 {@code @ConditionalOnBean(MeterRegistry)} 与自动配置排序；没装 actuator 的进程里它只是一个没人用的 bean。
 */
@AutoConfiguration
@ConditionalOnClass(MeterBinder.class)
public class ObservabilityAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public AppInfoMetrics appInfoMetrics(Environment environment) {
		return new AppInfoMetrics(environment);
	}

}
