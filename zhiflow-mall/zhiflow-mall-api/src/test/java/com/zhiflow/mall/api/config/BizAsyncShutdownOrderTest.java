package com.zhiflow.mall.api.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bizAsyncExecutor 停机收尾的先后（10-18 第四轮审查）。
 * <p>
 * 「停机等已提交任务最多 30 秒」只有在任务要用的东西还活着时才有意义。Valkey 的 {@code LettuceConnectionFactory} 是
 * SmartLifecycle（phase 0），在生命周期停止阶段就停了（之后取连接直接抛 IllegalStateException）；Druid 数据源、
 * {@code mqProducer} 在销毁单例阶段关，跟执行器谁先谁后取决于 bean 创建顺序。执行器若只靠 destroyMethod 在销毁阶段 close，
 * 这 30 秒里跑的任务碰 Redis 必失败、碰 DB / MQ 看运气。这里锁住：执行器在 Web 停服之后、Lettuce 之前收尾，
 * 收尾期间（含等名额的任务）Redis / 数据源 / MQ producer 都还在。
 * </p>
 */
class BizAsyncShutdownOrderTest {

	@Test
	@DisplayName("停机阶段：晚于 gRPC / Web 停服，早于 Lettuce（phase 0）；不随 pause 关闭")
	void shutdownPhaseSitsBetweenWebServerAndLettuce() {
		AsyncConfig.BizAsyncTaskExecutor executor = AsyncConfig.newBizAsyncExecutor(1, null);
		try {
			assertTrue(executor.getPhase() < WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE,
					"必须在 Web 停服之后才收尾（请求排空前还会有新任务提交）");
			assertTrue(executor.getPhase() < WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE);
			assertTrue(executor.getPhase() < Integer.MAX_VALUE, "gRPC 服务端 GrpcServerLifecycle 的 phase 是 Integer.MAX_VALUE");
			assertTrue(executor.getPhase() > new LettuceConnectionFactory().getPhase(),
					"必须早于 Lettuce 停止：Lettuce 停了以后任务里取 Redis 连接直接抛异常");
			assertFalse(executor.isPauseable(), "close() 不可逆，pause 时不能跟着关");
			assertTrue(executor.isRunning());
		}
		finally {
			executor.close();
		}
	}

	@Test
	@DisplayName("容器关闭：执行器收尾（正在执行的 + 等名额的）期间，Redis（phase 0 生命周期）、数据源、MQ producer（销毁阶段）都还活着")
	void drainRunsWhileRedisDataSourceAndProducerAreStillAlive() throws Exception {
		List<String> seen = new CopyOnWriteArrayList<>();
		CountDownLatch resume = new CountDownLatch(1);
		CountDownLatch bothDone = new CountDownLatch(2);
		FakeRedis redis;
		FakeWebServer web;
		AsyncConfig.BizAsyncTaskExecutor executor;
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.getEnvironment().getPropertySources()
					.addFirst(new MapPropertySource("test", Map.of("zhiflow.biz-async.concurrency-limit", "1")));
			// AsyncConfig 先注册 ⇒ bizAsyncExecutor 先于下面的数据源 / producer 创建 ⇒ 销毁阶段反而后于它们关（最坏情况）
			ctx.register(AsyncConfig.class, Infra.class);
			ctx.refresh();
			executor = ctx.getBean("bizAsyncExecutor", AsyncConfig.BizAsyncTaskExecutor.class);
			redis = ctx.getBean(FakeRedis.class);
			web = ctx.getBean(FakeWebServer.class);
			FakeDataSource dataSource = ctx.getBean(FakeDataSource.class);
			FakeProducer producer = ctx.getBean(FakeProducer.class);
			web.executor = executor;

			Runnable task = () -> {
				try {
					resume.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					seen.add("interrupted");
					return;
				}
				seen.add("redis.stopped=" + redis.stopped.get() + ",ds.closed=" + dataSource.closed.get()
						+ ",producer.shutdown=" + producer.shutdown.get());
				bothDone.countDown();
			};
			executor.execute(task); // 拿到名额、在执行
			executor.execute(task); // 在等名额
			assertTrue(BizAsyncExecutorTest.waitUntil(() -> executor.activeCount() == 1 && executor.waitingCount() == 1));

			// 关闭开始 300ms 后放行任务：若执行器在 Lettuce / 数据源 / producer 之后才收尾，这时它们早已停了
			Thread.ofPlatform().name("resume-timer").start(() -> {
				try {
					Thread.sleep(300);
				}
				catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				resume.countDown();
			});
		}
		assertTrue(bothDone.await(5, TimeUnit.SECONDS), "两个任务都必须跑完：" + seen);
		assertEquals(List.of("redis.stopped=false,ds.closed=false,producer.shutdown=false",
				"redis.stopped=false,ds.closed=false,producer.shutdown=false"), seen,
				"收尾期间任务用到的 Redis / 数据源 / MQ producer 必须都还活着");
		assertTrue(redis.stopped.get(), "容器关闭后 Redis 照常停");
		assertTrue(web.executorWasActiveWhenStopped.get(), "Web 停服时执行器必须还在收任务（请求排空期间的任务不能被拒）");
		assertFalse(executor.isActive());
		assertEquals(0, executor.abandonedCount());
	}

	@Configuration
	static class Infra {

		@Bean
		static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
			return new PropertySourcesPlaceholderConfigurer();
		}

		@Bean
		SimpleMeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}

		@Bean
		FakeRedis redis() {
			return new FakeRedis();
		}

		@Bean
		FakeWebServer webServer() {
			return new FakeWebServer();
		}

		@Bean
		FakeDataSource dataSource() {
			return new FakeDataSource();
		}

		@Bean
		FakeProducer mqProducer() {
			return new FakeProducer();
		}
	}

	/** 同 LettuceConnectionFactory：SmartLifecycle，phase 取真实类的默认值。 */
	static final class FakeRedis implements SmartLifecycle {

		final AtomicBoolean stopped = new AtomicBoolean();

		private volatile boolean running;

		@Override
		public void start() {
			running = true;
		}

		@Override
		public void stop() {
			stopped.set(true);
			running = false;
		}

		@Override
		public boolean isRunning() {
			return running;
		}

		@Override
		public int getPhase() {
			return new LettuceConnectionFactory().getPhase();
		}
	}

	/** 同 Spring Boot 的 Web 停服生命周期（phase = START_STOP_LIFECYCLE_PHASE）。 */
	static final class FakeWebServer implements SmartLifecycle {

		final AtomicBoolean executorWasActiveWhenStopped = new AtomicBoolean();

		volatile AsyncConfig.BizAsyncTaskExecutor executor;

		private volatile boolean running;

		@Override
		public void start() {
			running = true;
		}

		@Override
		public void stop() {
			AsyncConfig.BizAsyncTaskExecutor e = executor;
			executorWasActiveWhenStopped.set(e != null && e.isActive());
			running = false;
		}

		@Override
		public boolean isRunning() {
			return running;
		}

		@Override
		public int getPhase() {
			return WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE;
		}
	}

	/** 同 Druid 数据源：销毁阶段关。 */
	static final class FakeDataSource implements DisposableBean {

		final AtomicBoolean closed = new AtomicBoolean();

		@Override
		public void destroy() {
			closed.set(true);
		}
	}

	/** 同 mqProducer（destroyMethod = shutdown）：销毁阶段关。 */
	static final class FakeProducer implements DisposableBean {

		final AtomicBoolean shutdown = new AtomicBoolean();

		@Override
		public void destroy() {
			shutdown.set(true);
		}
	}

}
