package com.zhiflow.mall.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.MethodMetadata;

/**
 * 虚拟线程观测开关 {@value #ENABLED_PROPERTY}（配置文件中设置），默认关，重启生效。
 *
 * <p>类路径上有 {@code micrometer-java21} 时，Boot 的 {@code JvmMetricsAutoConfiguration} 只凭类存在就注册
 * {@code VirtualThreadMetrics}，没有属性可关；而它在构造时就建一个 JFR {@code RecordingStream}、绑定时启动，
 * 属于新的常驻开销。开关关（缺失、{@code false}、值写错）时在 Bean 定义阶段把这个 Bean 摘掉，进程与加依赖前一致；
 * 开关开时不干预，Boot 照常注册 {@code jvm.threads.virtual.*}。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class VirtualThreadObservabilityConfig {

	public static final String ENABLED_PROPERTY = "zhiflow.perf.virtual-thread-observability.enabled";

	static final String VIRTUAL_THREAD_METRICS_CLASS = "io.micrometer.java21.instrument.binder.jdk.VirtualThreadMetrics";

	@Bean
	static BeanDefinitionRegistryPostProcessor virtualThreadObservabilitySwitch(Environment environment) {
		return new Switch(environment);
	}

	static boolean enabled(Environment environment) {
		try {
			return Boolean.TRUE.equals(environment.getProperty(ENABLED_PROPERTY, Boolean.class, Boolean.FALSE));
		}
		catch (RuntimeException ex) {
			log.warn("{} 的值无法解析为布尔，按关闭处理: {}", ENABLED_PROPERTY, ex.toString());
			return false;
		}
	}

	static boolean isVirtualThreadMetrics(BeanDefinition definition) {
		if (VIRTUAL_THREAD_METRICS_CLASS.equals(definition.getBeanClassName())) {
			return true;
		}
		if (definition instanceof AnnotatedBeanDefinition annotated) {
			MethodMetadata factoryMethod = annotated.getFactoryMethodMetadata();
			if (factoryMethod == null) {
				return false;
			}
			// Boot 的工厂方法签名返回 MeterBinder，按声明类 + 方法名认；自定义的按返回类型认
			return VIRTUAL_THREAD_METRICS_CLASS.equals(factoryMethod.getReturnTypeName())
					|| (factoryMethod.getDeclaringClassName().endsWith("JvmMetricsAutoConfiguration$VirtualThreadMetricsConfiguration")
							&& "virtualThreadMetrics".equals(factoryMethod.getMethodName()));
		}
		return false;
	}

	static final class Switch implements BeanDefinitionRegistryPostProcessor {

		private final Environment environment;

		Switch(Environment environment) {
			this.environment = environment;
		}

		@Override
		public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
			if (enabled(this.environment)) {
				log.info("{}=true：保留虚拟线程指标（JFR 事件流）", ENABLED_PROPERTY);
				return;
			}
			for (String name : registry.getBeanDefinitionNames()) {
				if (isVirtualThreadMetrics(registry.getBeanDefinition(name))) {
					registry.removeBeanDefinition(name);
					log.info("{} 未开启：不注册虚拟线程指标 Bean {}", ENABLED_PROPERTY, name);
				}
			}
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		}

	}

}
