package com.zhiflow.mall.api.config;

import com.zhiflow.common.data.tenant.TenantContextHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code AsyncConfig} 实现 {@code AsyncConfigurer} 之后，整条装配链不会炸。
 * <p>
 * mall-api 模块没有 {@code @SpringBootTest} 冒烟测试（grep 确认），本类用等量结构的
 * {@code AnnotationConfigApplicationContext} 代替一次完整的 Spring Boot 启动，复现真实风险点：
 * </p>
 * <ul>
 *   <li>新增第 4 个 {@code Executor} 类型 bean 后，另外 3 个按 {@code @Qualifier} 精确取名的消费方
 *   （如 {@code UserDashboardServiceImpl} 对 {@code dbFanoutExecutor} 的真实用法）
 *   依旧各自解析到正确的 bean，不出现 {@code NoUniqueBeanDefinitionException}；</li>
 *   <li>不带名字的 {@code @Async} 走新的 {@code bizAsyncExecutor}（线程名 {@code mall-biz-async-}），
 *   带名字的 {@code @Async("asyncExecutor")}（对应 {@code TradeEventEmitter}）完全不受影响。</li>
 * </ul>
 */
class AsyncConfigurerWiringTest {

	@AfterEach
	void clearTenant() {
		TenantContextHolder.clear();
	}

	@Test
	@DisplayName("容器能正常 refresh；3 个按名字取用的 Executor/ExecutorService 消费方各自拿到对应 bean，互不冲突")
	void contextRefreshesAndQualifiedConsumersResolve() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.register(AsyncConfig.class, TestSupportConfig.class, QualifiedConsumers.class);
			assertDoesNotThrow(ctx::refresh, "新增 bizAsyncExecutor 后容器仍应正常启动，不应抛 NoUniqueBeanDefinitionException 等装配异常");

			QualifiedConsumers consumers = ctx.getBean(QualifiedConsumers.class);
			assertNotNull(consumers.virtualThreadExecutor);
			assertNotNull(consumers.dbFanoutExecutor);
			assertNotNull(consumers.asyncExecutor);
			assertNotSame(consumers.virtualThreadExecutor, consumers.dbFanoutExecutor,
					"两个不同名字的 bean 不能被混淆成同一个实例");
		}
	}

	@Test
	@DisplayName("不带名字的 @Async 走 bizAsyncExecutor（mall-biz-async- 线程）；带名字 @Async(\"asyncExecutor\") 走 async- 线程，互不影响")
	void unnamedAsyncUsesBizPoolNamedAsyncUnaffected() throws Exception {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.register(AsyncConfig.class, TestSupportConfig.class, AsyncProbe.class);
			ctx.refresh();

			AsyncProbe probe = ctx.getBean(AsyncProbe.class);
			CompletableFuture<String> unnamed = probe.unnamedAsync();
			CompletableFuture<String> named = probe.namedAsync();

			String unnamedThread = unnamed.get(5, TimeUnit.SECONDS);
			assertTrue(unnamedThread.startsWith("mall-biz-async-"), "不带名字的 @Async 必须走 bizAsyncExecutor（B2）");
			assertTrue(unnamedThread.endsWith("|virtual"), "bizAsyncExecutor 每个任务一条虚拟线程，实际=" + unnamedThread);
			assertTrue(named.get(5, TimeUnit.SECONDS).startsWith("async-"),
					"带名字的 @Async(\"asyncExecutor\")（如 TradeEventEmitter）不受影响，仍走埋点池");
		}
	}

	@Configuration
	static class TestSupportConfig {

		/** @Value 的 ${...:默认值} 占位符解析需要这个 bean，否则 dbFanoutExecutor 的 permits 参数解析不出来。 */
		@Bean
		static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
			return new PropertySourcesPlaceholderConfigurer();
		}

		@Bean
		SimpleMeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}
	}

	@Component
	static class QualifiedConsumers {

		@Autowired
		@Qualifier("virtualThreadExecutor")
		ExecutorService virtualThreadExecutor;

		@Autowired
		@Qualifier("dbFanoutExecutor")
		ExecutorService dbFanoutExecutor;

		@Autowired
		@Qualifier("asyncExecutor")
		Executor asyncExecutor;
	}

	@Component
	static class AsyncProbe {

		@Async
		CompletableFuture<String> unnamedAsync() {
			Thread t = Thread.currentThread();
			return CompletableFuture.completedFuture(t.getName() + (t.isVirtual() ? "|virtual" : "|platform"));
		}

		@Async("asyncExecutor")
		CompletableFuture<String> namedAsync() {
			return CompletableFuture.completedFuture(Thread.currentThread().getName());
		}
	}

}
