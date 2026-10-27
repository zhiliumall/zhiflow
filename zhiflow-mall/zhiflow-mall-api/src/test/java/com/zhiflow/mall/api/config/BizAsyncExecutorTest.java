package com.zhiflow.mall.api.config;

import com.zhiflow.common.core.observability.TraceIds;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bizAsyncExecutor：在虚拟线程里等名额，提交方不等。
 * <p>
 * 旧版 32 线程 + 1 万队列 + CallerRuns 在 1500/1800 单/秒压测里被打满，CallerRuns 让 AFTER_COMMIT 监听器在请求线程的
 * 事务收尾阶段执行，约 3 万条下单 / 自动关单消息没发出去；中间版「提交方拿名额、满了阻塞」又会让攥着 DB 连接的请求线程
 * 与等连接的任务互等。这里锁住新语义：提交即返回、同时执行数不超过上限、等名额的数目看得见、停机等排队任务、超时的结局明确。
 * </p>
 */
class BizAsyncExecutorTest {

	@AfterEach
	void clearTenant() {
		TenantContextHolder.clear();
	}

	private static AsyncConfig.BizAsyncTaskExecutor realBean(MeterRegistry registry, int limit) {
		DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
		if (registry != null) {
			bf.registerSingleton("meterRegistry", registry);
		}
		return new AsyncConfig().bizAsyncExecutor(bf.getBeanProvider(MeterRegistry.class), limit);
	}

	@Test
	@DisplayName("bean 本身：SimpleAsyncTaskExecutor、虚拟线程、名额 2000、线程名前缀 mall-biz-async-、不用 Spring 的提交方限流；上限配成非法值按 2000")
	void beanIsVirtualThreadExecutorWithInThreadPermits() throws Exception {
		AsyncConfig.BizAsyncTaskExecutor executor = realBean(new SimpleMeterRegistry(), AsyncConfig.BIZ_ASYNC_DEFAULT_LIMIT);
		try {
			assertInstanceOf(SimpleAsyncTaskExecutor.class, executor);
			assertEquals(2000, executor.permitLimit());
			assertFalse(executor.isThrottleActive(), "不能用 Spring 的 setConcurrencyLimit：那是在提交方线程拿名额、满了阻塞提交方");
			assertEquals("mall-biz-async-", executor.getThreadNamePrefix());

			Thread caller = Thread.currentThread();
			Thread ran = executor.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
			assertTrue(ran.isVirtual(), "任务必须跑在虚拟线程上");
			assertTrue(ran.getName().startsWith("mall-biz-async-"), "线程名=" + ran.getName());
			assertNotSame(caller, ran);
		}
		finally {
			executor.close();
		}

		for (int bad : new int[] { 0, -1 }) {
			AsyncConfig.BizAsyncTaskExecutor fallback = realBean(null, bad);
			assertEquals(AsyncConfig.BIZ_ASYNC_DEFAULT_LIMIT, fallback.permitLimit(), "非法上限不能原样生效");
			fallback.close();
		}
	}

	@Test
	@DisplayName("提交方不阻塞：上限 3 时提交 10 个阻塞任务，提交调用立即返回；同时执行 ≤ 3，其余 7 个在虚拟线程里等名额（waiting=7）")
	void submitterNeverBlocksAndConcurrencyStaysWithinLimit() throws Exception {
		int limit = 3;
		int total = 10;
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AsyncConfig.BizAsyncTaskExecutor executor = AsyncConfig.newBizAsyncExecutor(limit, registry);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch allDone = new CountDownLatch(total);
		AtomicInteger running = new AtomicInteger();
		AtomicInteger maxRunning = new AtomicInteger();
		List<Thread> ranOn = new CopyOnWriteArrayList<>();
		Runnable blocking = () -> {
			ranOn.add(Thread.currentThread());
			maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
			await(release);
			running.decrementAndGet();
			allDone.countDown();
		};
		// 从单独的「请求线程」提交：提交方若被挡住，这条线程会卡住，而不是把测试线程卡死
		Thread submitter = Thread.ofPlatform().name("request-thread-sim").start(() -> {
			for (int i = 0; i < total; i++) {
				executor.execute(blocking);
			}
		});
		try {
			submitter.join(2_000);
			assertFalse(submitter.isAlive(), () -> "提交方必须立即返回、不等名额，实际卡在：" + submitter.getState());

			assertTrue(waitUntil(() -> executor.activeCount() == limit && executor.waitingCount() == total - limit),
					() -> "应有 " + limit + " 个在执行、" + (total - limit) + " 个在等名额，实际 active=" + executor.activeCount()
							+ " waiting=" + executor.waitingCount());
			assertEquals(limit, gauge(registry, AsyncConfig.M_BIZ_ASYNC_ACTIVE));
			assertEquals(total - limit, gauge(registry, AsyncConfig.M_BIZ_ASYNC_WAITING));
			assertEquals(total - limit, executor.throttledCount());
			assertEquals(total - limit, registry.get(AsyncConfig.M_BIZ_ASYNC_THROTTLED).tag("executor", "bizAsyncExecutor").counter().count());
			assertEquals(limit, running.get(), "名额没空出来之前，多出来的任务不能开始执行");

			release.countDown();
			assertTrue(allDone.await(5, TimeUnit.SECONDS), "放行后 10 个任务都必须执行完，一个不丢");
			assertTrue(maxRunning.get() <= limit, "同时执行的任务数超过上限：" + maxRunning.get());
			for (Thread t : ranOn) {
				assertNotSame(submitter, t, "任务不能在提交方线程上执行");
				assertTrue(t.isVirtual());
			}
			assertEquals(total - limit, registry.get(AsyncConfig.M_BIZ_ASYNC_THROTTLE_WAIT).tag("executor", "bizAsyncExecutor").timer().count());
			assertTrue(waitUntil(() -> executor.activeCount() == 0 && executor.waitingCount() == 0));
			assertEquals(0.0, gauge(registry, AsyncConfig.M_BIZ_ASYNC_ACTIVE));
			assertEquals(0.0, gauge(registry, AsyncConfig.M_BIZ_ASYNC_WAITING));
		}
		finally {
			release.countDown();
			submitter.join(5_000);
			executor.close();
		}
	}

	@Test
	@DisplayName("没有 MeterRegistry：正常跑、等名额再放行都不 NPE")
	void worksWithoutRegistry() throws Exception {
		AsyncConfig.BizAsyncTaskExecutor executor = AsyncConfig.newBizAsyncExecutor(1, null);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		try {
			executor.execute(() -> {
				await(release);
				done.countDown();
			});
			executor.execute(done::countDown);
			assertTrue(waitUntil(() -> executor.waitingCount() == 1));
			release.countDown();
			assertTrue(done.await(5, TimeUnit.SECONDS));
			assertEquals(1, executor.throttledCount());
		}
		finally {
			release.countDown();
			executor.close();
		}
	}

	@Test
	@DisplayName("租户上下文：按提交那一刻传（含 MDC tenant.id，等过名额的任务也一样）；任务里改租户不回流到提交方、也不残留到下一个任务")
	void tenantContextFollowsSubmitterAndDoesNotLeak() throws Exception {
		AsyncConfig.BizAsyncTaskExecutor executor = AsyncConfig.newBizAsyncExecutor(1, null);
		try {
			Set<String> threadIds = new HashSet<>();
			for (String tenant : new String[] { "tenant-A", "tenant-B", "tenant-A" }) {
				TenantContextHolder.setTenantId(tenant);
				String[] seen = executor.submit(() -> {
					String[] r = { TenantContextHolder.getTenantId(), MDC.get(TraceIds.MDC_TENANT_ID),
							String.valueOf(Thread.currentThread().threadId()) };
					// 故意不清：模拟任务里切租户、忘了还原
					TenantContextHolder.setTenantId("dirty-" + tenant);
					return r;
				}).get(5, TimeUnit.SECONDS);
				assertEquals(tenant, seen[0], "任务读到的必须是提交那一刻的租户");
				assertEquals(tenant, seen[1], "MDC tenant.id 跟着租户一起传");
				assertTrue(threadIds.add(seen[2]), "虚拟线程不复用：每个任务一条新线程");
				assertEquals(tenant, TenantContextHolder.getTenantId(), "任务里改租户不能回流到提交方线程");
			}

			// 等过名额的任务：提交时是 tenant-C，提交后提交方换成 tenant-D，任务拿到名额时读到的仍是 C
			CountDownLatch release = new CountDownLatch(1);
			executor.execute(() -> await(release));
			TenantContextHolder.setTenantId("tenant-C");
			Future<String> waited = executor.submit(TenantContextHolder::getTenantId);
			TenantContextHolder.setTenantId("tenant-D");
			assertTrue(waitUntil(() -> executor.waitingCount() == 1));
			release.countDown();
			assertEquals("tenant-C", waited.get(5, TimeUnit.SECONDS));

			TenantContextHolder.clear();
			assertNull(executor.submit(TenantContextHolder::getTenantId).get(5, TimeUnit.SECONDS),
					"提交方没有租户时，任务里也必须没有（上一个任务留下的 dirty-* 不能残留）");
			assertNull(TenantContextHolder.getTenantId());
		}
		finally {
			executor.close();
		}
	}

	@Test
	@DisplayName("Spring 容器关闭：正在执行的和在等名额的都等它们跑完（最多 30 秒）；关闭之后再提交直接拒绝")
	void contextCloseWaitsForRunningAndWaitingTasks() throws Exception {
		AsyncConfig.BizAsyncTaskExecutor executor;
		int taskCount = 6;
		AtomicInteger ran = new AtomicInteger();
		long elapsedMillis;
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.getEnvironment().getPropertySources()
					.addFirst(new MapPropertySource("test", Map.of("zhiflow.biz-async.concurrency-limit", "2")));
			ctx.register(AsyncConfig.class, TestSupportConfig.class);
			ctx.refresh();

			executor = ctx.getBean("bizAsyncExecutor", AsyncConfig.BizAsyncTaskExecutor.class);
			assertEquals(2, executor.permitLimit(), "上限从配置读");
			// 任务卡在门闩上（不用 sleep：避免「2 在跑 4 在等」这个快照被慢机器错过），关闭开始 300ms 后才放行
			CountDownLatch resume = new CountDownLatch(1);
			for (int i = 0; i < taskCount; i++) {
				executor.execute(() -> {
					try {
						if (!resume.await(10, TimeUnit.SECONDS)) {
							return;
						}
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
					ran.incrementAndGet();
				});
			}
			AsyncConfig.BizAsyncTaskExecutor e = executor;
			assertTrue(waitUntil(() -> e.activeCount() == 2 && e.waitingCount() == 4), "2 个在跑、4 个在等名额");

			Thread.ofPlatform().name("resume-timer").start(() -> {
				try {
					Thread.sleep(300);
				}
				catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				resume.countDown();
			});
			long startNanos = System.nanoTime();
			ctx.close();
			elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
		}
		assertEquals(taskCount, ran.get(), "容器关闭必须等已提交的任务（含在等名额的）全部跑完");
		assertTrue(elapsedMillis >= 250, "任务放行前 close 必须一直等着，实际耗时=" + elapsedMillis + "ms");
		assertEquals(0, executor.abandonedCount());
		assertFalse(executor.isActive());
		assertThrows(TaskRejectedException.class, () -> executor.execute(ran::incrementAndGet),
				"关闭之后的提交必须明确拒绝，不能静默丢");
	}

	@Test
	@DisplayName("停机等待超时：仍在执行的被 interrupt；仍在等名额的不执行、Future 被取消、计入 abandoned")
	void closeTimeoutInterruptsRunningAndAbandonsWaiting() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AsyncConfig.BizAsyncTaskExecutor executor = AsyncConfig.newBizAsyncExecutor(1, registry);
		executor.setTaskTerminationTimeout(300); // 生产 30 秒，测试缩短
		CountDownLatch interrupted = new CountDownLatch(1);
		AtomicInteger waitingTaskRan = new AtomicInteger();
		executor.execute(() -> {
			try {
				Thread.sleep(60_000);
			}
			catch (InterruptedException e) {
				interrupted.countDown();
			}
		});
		Future<?> waiting = executor.submit(waitingTaskRan::incrementAndGet);
		assertTrue(waitUntil(() -> executor.activeCount() == 1 && executor.waitingCount() == 1));

		long startNanos = System.nanoTime();
		executor.close();
		long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

		assertTrue(elapsedMillis >= 250 && elapsedMillis < 5_000, "应等到超时（300ms）再返回，实际=" + elapsedMillis + "ms");
		assertTrue(interrupted.await(5, TimeUnit.SECONDS), "超时后仍在执行的任务必须被 interrupt");
		assertTrue(waitUntil(waiting::isCancelled), "仍在等名额的任务：Future 必须被取消");
		assertEquals(0, waitingTaskRan.get(), "仍在等名额的任务不执行");
		assertTrue(waitUntil(() -> executor.abandonedCount() == 1));
		assertEquals(1.0, registry.get(AsyncConfig.M_BIZ_ASYNC_ABANDONED).tag("executor", "bizAsyncExecutor").counter().count());
		assertTrue(waitUntil(() -> executor.waitingCount() == 0 && executor.activeCount() == 0));
	}

	@Configuration
	static class TestSupportConfig {

		/** @Value 的 ${...:默认值} 占位符解析需要这个 bean。 */
		@Bean
		static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
			return new PropertySourcesPlaceholderConfigurer();
		}

		@Bean
		SimpleMeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}
	}

	private static double gauge(SimpleMeterRegistry registry, String name) {
		return registry.get(name).tag("executor", "bizAsyncExecutor").gauge().value();
	}

	static boolean waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return true;
			}
			Thread.sleep(10);
		}
		return condition.getAsBoolean();
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
