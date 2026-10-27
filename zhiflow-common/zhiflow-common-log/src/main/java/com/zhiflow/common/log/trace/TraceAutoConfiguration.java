package com.zhiflow.common.log.trace;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * servlet 服务的链路标识装配：{@link TraceMdcFilter} + {@link UserMdcInterceptor}。
 *
 * <p>走 {@code AutoConfiguration.imports}：各可执行模块的 {@code @ComponentScan} 不含 common-log 的包，
 * 挂 {@code @Component} 扫不到（编译、单测全绿，只在运行期缺 bean）。
 *
 * <p>逃生舱 {@code observability.trace-mdc.enabled=false}：不重发版即可整体摘掉（配置改完重启生效）。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "observability.trace-mdc", name = "enabled", havingValue = "true",
		matchIfMissing = true)
public class TraceAutoConfiguration {

	/** 排在 boot 形态的 from 头剥离（MIN_VALUE）与恶意路径拦截（MIN_VALUE+1）之后、其余一切之前。 */
	public static final int TRACE_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

	@Bean
	@ConditionalOnMissingBean(name = "traceMdcFilterRegistration")
	public FilterRegistrationBean<TraceMdcFilter> traceMdcFilterRegistration() {
		FilterRegistrationBean<TraceMdcFilter> registration = new FilterRegistrationBean<>(new TraceMdcFilter());
		registration.setName("traceMdcFilter");
		registration.setOrder(TRACE_FILTER_ORDER);
		registration.addUrlPatterns("/*");
		registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
		return registration;
	}

	@Bean
	public WebMvcConfigurer userMdcWebMvcConfigurer() {
		return new WebMvcConfigurer() {
			@Override
			public void addInterceptors(InterceptorRegistry registry) {
				registry.addInterceptor(new UserMdcInterceptor()).order(Ordered.LOWEST_PRECEDENCE);
			}
		};
	}

}
