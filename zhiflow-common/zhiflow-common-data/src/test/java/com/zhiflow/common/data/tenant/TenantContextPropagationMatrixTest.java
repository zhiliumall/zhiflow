package com.zhiflow.common.data.tenant;

import com.alibaba.ttl.threadpool.TtlExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「两个租户交替并发」：异步任务读到的租户必须是<b>提交者</b>的，不能是建线程那一刻的。
 *
 * <p>覆盖本仓实际在用的几类执行器，调用方分平台线程和虚拟线程两种 —— 开虚拟线程后，
 * Tomcat 请求线程就是虚拟线程，这里的第二种调用方就是那时的真实形态。
 *
 * <p>每个执行器先在「stale」租户下把池线程预先建出来（实际就是最早几个请求干的），
 * 再由 A / B 两个租户交替提交。对照组 {@link #unwrappedPoolIsTheBugThisGuardsAgainst}
 * 证明这组断言确实抓得住问题：同样的步骤、池不做 TTL 包装，读到的就是 stale。
 */
class TenantContextPropagationMatrixTest {

	private static final int TASKS_PER_TENANT = 100;

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	record Case(String name, Function<ExecutorService, ExecutorService> wrap, boolean prewarmable,
			java.util.function.Supplier<ExecutorService> factory) {
		@Override
		public String toString() {
			return name;
		}
	}

	static Stream<Case> executors() {
		return Stream.of(
				// mall-api asyncExecutor / mall-admin 大屏、交易埋点 / im 工作流：TTL 包装的平台线程池
				new Case("TTL 包装的平台线程池", TtlExecutors::getTtlExecutorService, true,
						() -> Executors.newFixedThreadPool(4)),
				// mall-api virtualThreadExecutor / 审计日志上报：每任务一个虚拟线程
				new Case("每任务一个虚拟线程", Function.identity(), false,
						() -> Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())),
				// 开关打开后 Boot 给 @Async / @Scheduled 用的：SimpleAsyncTaskExecutor(virtualThreads=true)
				new Case("Spring SimpleAsyncTaskExecutor（虚拟线程）", Function.identity(), false,
						() -> adapt(virtualSimpleAsync())),
				// mall-api / mall-admin 的无名 @Async 现状：Spring 兜底的 SimpleAsyncTaskExecutor（平台线程）
				new Case("Spring SimpleAsyncTaskExecutor（平台线程）", Function.identity(), false,
						() -> adapt(new SimpleAsyncTaskExecutor()))
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("executors")
	@DisplayName("平台线程调用方：A/B 交替提交，各自读到自己的租户")
	void platformCallers(Case c) throws Exception {
		ExecutorService pool = prepare(c);
		try {
			assertAllSeeOwnTenant(pool::submit, false);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("executors")
	@DisplayName("虚拟线程调用方：A/B 交替提交，各自读到自己的租户")
	void virtualCallers(Case c) throws Exception {
		ExecutorService pool = prepare(c);
		try {
			assertAllSeeOwnTenant(pool::submit, true);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("TenantAwareAsync（公共 ForkJoin 池）：平台 / 虚拟线程调用方都读到自己的租户")
	void tenantAwareAsync() throws Exception {
		// 先在 stale 下用裸 runAsync 把公共池线程带上旧值（它们继承建线程那一刻的租户）
		TenantContextHolder.setTenantId("stale");
		List<CompletableFuture<?>> warm = new ArrayList<>();
		for (int i = 0; i < 32; i++) {
			warm.add(CompletableFuture.runAsync(TenantContextPropagationMatrixTest::spin));
		}
		CompletableFuture.allOf(warm.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
		TenantContextHolder.clear();

		Submitter viaHelper = new Submitter() {
			@Override
			public <T> Future<T> submit(Callable<T> task) {
				return TenantAwareAsync.supplyAsync(() -> {
					try {
						return task.call();
					}
					catch (Exception e) {
						throw new IllegalStateException(e);
					}
				});
			}
		};
		assertAllSeeOwnTenant(viaHelper, false);
		assertAllSeeOwnTenant(viaHelper, true);
	}

	@Test
	@DisplayName("对照：同样的步骤，池不做 TTL 包装，任务读到的是建线程时的 stale")
	void unwrappedPoolIsTheBugThisGuardsAgainst() throws Exception {
		ThreadPoolExecutor raw = (ThreadPoolExecutor) Executors.newFixedThreadPool(4);
		try {
			TenantContextHolder.setTenantId("stale");
			raw.prestartAllCoreThreads();
			TenantContextHolder.setTenantId("tenant-B");
			String seen = raw.submit(TenantContextHolder::getTenantId).get(5, TimeUnit.SECONDS);
			assertEquals("stale", seen, "对照失效：裸池应当读到建线程时的旧租户，否则上面的断言证明不了什么");
		}
		finally {
			raw.shutdownNow();
		}
	}

	interface Submitter {
		<T> Future<T> submit(Callable<T> task);
	}

	private ExecutorService prepare(Case c) {
		ExecutorService base = c.factory().get();
		if (c.prewarmable() && base instanceof ThreadPoolExecutor tpe) {
			TenantContextHolder.setTenantId("stale");
			tpe.prestartAllCoreThreads();
			TenantContextHolder.clear();
		}
		return c.wrap().apply(base);
	}

	/**
	 * A、B 两个租户各起 {@link #TASKS_PER_TENANT} 个调用方，交替提交，全部任务同时开跑；
	 * 每个任务读 {@link TenantContextHolder}，必须等于它的提交者设置的租户。
	 */
	private static void assertAllSeeOwnTenant(Submitter submitter, boolean virtualCallers) throws Exception {
		ConcurrentLinkedQueue<String> mismatches = new ConcurrentLinkedQueue<>();
		CountDownLatch start = new CountDownLatch(1);
		List<Thread> callers = new ArrayList<>();
		for (int i = 0; i < TASKS_PER_TENANT * 2; i++) {
			String tenant = (i % 2 == 0) ? "tenant-A" : "tenant-B";
			Runnable caller = () -> {
				TenantContextHolder.setTenantId(tenant);
				try {
					start.await();
					String seen = submitter.submit(() -> {
						spin();
						return TenantContextHolder.getTenantId();
					}).get(10, TimeUnit.SECONDS);
					if (!tenant.equals(seen)) {
						mismatches.add(tenant + " 提交，任务读到 " + seen);
					}
				}
				catch (Exception e) {
					mismatches.add(tenant + " 提交失败：" + e);
				}
				finally {
					TenantContextHolder.clear();
				}
			};
			callers.add(virtualCallers ? Thread.ofVirtual().unstarted(caller) : Thread.ofPlatform().unstarted(caller));
		}
		callers.forEach(Thread::start);
		start.countDown();
		for (Thread t : callers) {
			t.join(TimeUnit.SECONDS.toMillis(30));
		}
		assertTrue(mismatches.isEmpty(), "租户串了 " + mismatches.size() + " 次，例如：" + mismatches.stream().limit(3).toList());
	}

	private static SimpleAsyncTaskExecutor virtualSimpleAsync() {
		SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("vt-async-");
		executor.setVirtualThreads(true);
		return executor;
	}

	/** 把 {@link SimpleAsyncTaskExecutor} 包成 {@link ExecutorService}，只为复用同一套断言。 */
	private static ExecutorService adapt(SimpleAsyncTaskExecutor executor) {
		return new java.util.concurrent.AbstractExecutorService() {
			private volatile boolean shutdown;

			@Override
			public void execute(Runnable command) {
				executor.execute(command);
			}

			@Override
			public void shutdown() {
				shutdown = true;
			}

			@Override
			public List<Runnable> shutdownNow() {
				shutdown = true;
				return List.of();
			}

			@Override
			public boolean isShutdown() {
				return shutdown;
			}

			@Override
			public boolean isTerminated() {
				return shutdown;
			}

			@Override
			public boolean awaitTermination(long timeout, TimeUnit unit) {
				return true;
			}
		};
	}

	/** 让任务重叠起来，放大「池线程被多个租户轮流用」的机会。 */
	private static void spin() {
		try {
			Thread.sleep(1);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
