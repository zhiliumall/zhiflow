package com.zhiflow.mall.api.config;

import com.alibaba.ttl.spi.TtlWrapper;
import com.alibaba.ttl.threadpool.TtlExecutors;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * asyncExecutor 拒绝策略：满了丢弃 + 计数，不抛。
 * <p>
 * 10-18 压测每秒 1000 单时这个池拒绝约 1.7 万次，原 AbortPolicy 每次都在下单请求线程的 afterCompletion 里
 * 抛 TaskRejectedException、打一条 ERROR 加整段堆栈。改完后要保证：不抛、丢了多少有数、TTL 上下文照传。
 */
class AsyncExecutorRejectionTest {

	@AfterEach
	void clearTenant() {
		TenantContextHolder.clear();
	}

	@Test
	@DisplayName("bean 本身：TTL 包装、内层 4/16/2048、拒绝策略是丢弃计数（不是 AbortPolicy）")
	void beanUsesCountingDiscardPolicyBehindTtlWrapper() {
		DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
		bf.registerSingleton("meterRegistry", new SimpleMeterRegistry());
		Executor bean = new AsyncConfig().asyncExecutor(bf.getBeanProvider(MeterRegistry.class));
		try {
			assertInstanceOf(TtlWrapper.class, bean, "必须保留 TTL 包装（租户上下文靠它传到异步线程）");
			@SuppressWarnings("unchecked")
			ThreadPoolExecutor inner = (ThreadPoolExecutor) ((TtlWrapper<Executor>) bean).unwrap();
			assertEquals(4, inner.getCorePoolSize());
			assertEquals(16, inner.getMaximumPoolSize());
			assertEquals(2048, inner.getQueue().remainingCapacity());
			assertInstanceOf(AsyncConfig.CountingDiscardPolicy.class, inner.getRejectedExecutionHandler());
		}
		finally {
			((ThreadPoolExecutor) ((TtlWrapper<?>) bean).unwrap()).shutdownNow();
		}
	}

	@Test
	@DisplayName("池满：经 TTL 包装 execute、经 Spring 适配器 submit 都不抛，每丢一个计数 +1；关停后再丢不计数")
	void fullPoolDiscardsAndCounts() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AsyncConfig.CountingDiscardPolicy policy = new AsyncConfig.CountingDiscardPolicy(registry);
		ThreadPoolTaskExecutor pool = AsyncConfig.newAsyncExecutor(1, 1, 1, policy);
		Executor ttl = TtlExecutors.getTtlExecutor(pool.getThreadPoolExecutor());
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(1);
		AtomicInteger ran = new AtomicInteger();
		try {
			// 1 条线程占住 + 队列 1 个 = 满
			ttl.execute(() -> {
				started.countDown();
				await(release);
				ran.incrementAndGet();
			});
			assertTrue(started.await(5, TimeUnit.SECONDS));
			ttl.execute(ran::incrementAndGet);

			assertDoesNotThrow(() -> ttl.execute(ran::incrementAndGet), "满了不抛");
			assertEquals(1, policy.rejected());
			assertEquals(1.0, rejectedCount(registry));

			// @Async 实际走的路径：Spring 把 Executor 适配成 TaskExecutorAdapter，原 AbortPolicy 在这里翻成 TaskRejectedException
			assertDoesNotThrow(() -> new TaskExecutorAdapter(ttl).submit(ran::incrementAndGet));
			assertEquals(2, policy.rejected());
			assertEquals(2.0, rejectedCount(registry));

			release.countDown();
			pool.shutdown();
			assertTrue(pool.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS));
			assertEquals(2, ran.get(), "占住的那个和排队的那个都跑完了，丢掉的两个没跑");

			assertDoesNotThrow(() -> ttl.execute(ran::incrementAndGet));
			assertEquals(2, policy.rejected(), "关停后的丢弃不算过载，不计数");
			assertEquals(2.0, rejectedCount(registry));
		}
		finally {
			release.countDown();
			pool.shutdown();
		}
	}

	@Test
	@DisplayName("没有 MeterRegistry 时只计本地数、不 NPE")
	void worksWithoutRegistry() {
		AsyncConfig.CountingDiscardPolicy policy = new AsyncConfig.CountingDiscardPolicy(null);
		ThreadPoolTaskExecutor pool = AsyncConfig.newAsyncExecutor(1, 1, 1, policy);
		try {
			assertDoesNotThrow(() -> policy.rejectedExecution(() -> { }, pool.getThreadPoolExecutor()));
			assertEquals(1, policy.rejected());
		}
		finally {
			pool.shutdown();
		}
	}

	@Test
	@DisplayName("TTL 上下文按提交那一刻传：线程在租户 A 下建出，之后在租户 B 下提交的任务读到 B")
	void tenantContextFollowsSubmitter() throws Exception {
		ThreadPoolTaskExecutor pool = AsyncConfig.newAsyncExecutor(2, 2, 16,
				new AsyncConfig.CountingDiscardPolicy(null));
		TaskExecutorAdapter adapter = new TaskExecutorAdapter(TtlExecutors.getTtlExecutor(pool.getThreadPoolExecutor()));
		try {
			TenantContextHolder.setTenantId("tenant-A");
			pool.getThreadPoolExecutor().prestartAllCoreThreads();

			for (String tenant : new String[] { "tenant-B", "tenant-A", "tenant-B" }) {
				TenantContextHolder.setTenantId(tenant);
				assertEquals(tenant, adapter.submit(TenantContextHolder::getTenantId).get(5, TimeUnit.SECONDS));
			}
		}
		finally {
			pool.shutdown();
		}
	}

	private static double rejectedCount(SimpleMeterRegistry registry) {
		return registry.get(AsyncConfig.M_ASYNC_REJECTED).tag("executor", "asyncExecutor").counter().count();
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
