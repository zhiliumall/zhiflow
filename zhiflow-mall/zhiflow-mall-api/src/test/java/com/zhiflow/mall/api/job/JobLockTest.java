package com.zhiflow.mall.api.job;

import com.zhiflow.api.upms.TenantApi;
import com.zhiflow.common.core.util.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时任务多实例互斥：同一时刻只有一个实例执行任务本体，其余本轮记「跳过」；四个任务都走这把锁。
 */
class JobLockTest {

	private final InMemoryLocks redis = new InMemoryLocks();

	@Test
	@DisplayName("两个实例同时触发：只有一个执行本体，另一个跳过且不算失败；结束后锁释放")
	void onlyOneInstanceRunsAtATime() throws Exception {
		JobLock instanceA = redis.newInstance();
		JobLock instanceB = redis.newInstance();
		CountDownLatch aInside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger bodies = new AtomicInteger();

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<JobResult> a = pool.submit(() -> instanceA.runExclusive("Sample", () -> {
				bodies.incrementAndGet();
				aInside.countDown();
				await(release);
				return JobResult.ok("A 跑完");
			}));
			assertTrue(aInside.await(5, TimeUnit.SECONDS), "实例 A 没进入任务本体");

			JobResult b = instanceB.runExclusive("Sample", () -> {
				bodies.incrementAndGet();
				return JobResult.ok("B 不该跑");
			});
			assertTrue(b.success(), "锁被占是跳过，不是失败");
			assertEquals("任务正在执行中，跳过", b.message());

			release.countDown();
			assertEquals("A 跑完", a.get(5, TimeUnit.SECONDS).message());
		}
		finally {
			pool.shutdownNow();
		}
		assertEquals(1, bodies.get(), "同一时刻只能有一个实例执行本体");
		assertTrue(redis.isFree("Sample"), "执行完必须释放锁");

		assertEquals("B 下一轮能跑", instanceB.runExclusive("Sample", () -> JobResult.ok("B 下一轮能跑")).message());
	}

	@Test
	@DisplayName("本体抛异常也释放锁；不同任务的锁互不影响")
	void releasesOnExceptionAndKeysArePerJob() {
		JobLock instance = redis.newInstance();
		try {
			instance.runExclusive("Sample", () -> {
				throw new IllegalStateException("boom");
			});
		}
		catch (IllegalStateException expected) {
			// 异常原样抛给调用方（各任务自己在本体里兜住并转成 JobResult）
		}
		assertTrue(redis.isFree("Sample"));

		redis.holdByAnotherInstance("Other");
		assertEquals("跑了", instance.runExclusive("Sample", () -> JobResult.ok("跑了")).message());
		assertFalse(redis.isFree("Other"), "别的实例持有的锁不能被本实例放掉");
	}

	@Test
	@DisplayName("Redis 不可用：本轮不执行、记失败（不是静默跳过）")
	void redisDownIsAFailure() {
		redis.broken = true;
		AtomicInteger bodies = new AtomicInteger();

		JobResult r = redis.newInstance().runExclusive("Sample", () -> {
			bodies.incrementAndGet();
			return JobResult.ok("不该跑");
		});

		assertFalse(r.success());
		assertEquals(0, bodies.get());
	}

	// ------------------------------------------------------------------ 四个任务都接了这把锁

	static Stream<Arguments> jobs() {
		return Stream.of(
				Arguments.of(OrderAutoCancelJob.LOCK_NAME,
						(Function<Deps, Object>) d -> new OrderAutoCancelJob(d.lock, null, d.tenantApi)),
				Arguments.of(OrderAutoReceiveJob.LOCK_NAME,
						(Function<Deps, Object>) d -> new OrderAutoReceiveJob(d.lock, null, null, d.tenantApi)),
				Arguments.of(PayOrderReconcileJob.LOCK_NAME,
						(Function<Deps, Object>) d -> new PayOrderReconcileJob(d.lock, null, null, null, d.tenantApi, null)),
				Arguments.of(StockHoldExpiryJob.LOCK_NAME,
						(Function<Deps, Object>) d -> new StockHoldExpiryJob(null, null, null, null, null, d.tenantApi, d.lock)));
	}

	@Test
	@SuppressWarnings("unchecked")
	@DisplayName("守卫：job 包里每个 @Scheduled 任务都在上面这张表里（新加任务漏接锁 = 多实例重复执行）")
	void everyScheduledJobIsCovered() {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
		Set<String> scheduled = new TreeSet<>();
		for (BeanDefinition bd : scanner.findCandidateComponents(JobLock.class.getPackageName())) {
			try {
				Class<?> type = Class.forName(bd.getBeanClassName());
				if (Arrays.stream(type.getDeclaredMethods()).anyMatch(m -> m.isAnnotationPresent(Scheduled.class))) {
					scheduled.add(type.getSimpleName());
				}
			}
			catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}
		}
		Set<String> covered = new TreeSet<>();
		Deps deps = new Deps(redis.newInstance());
		jobs().forEach(a -> covered.add(((Function<Deps, Object>) a.get()[1]).apply(deps).getClass().getSimpleName()));
		assertEquals(4, scheduled.size(), "判据自检：job 包里应扫到 4 个定时任务，实际 " + scheduled);
		assertEquals(scheduled, covered);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("jobs")
	@DisplayName("锁被别的实例占着：任务本体一行都不跑")
	void jobSkipsWhenAnotherInstanceHoldsTheLock(String lockName, Function<Deps, Object> factory) {
		Deps deps = new Deps(redis.newInstance());
		redis.holdByAnotherInstance(lockName);

		JobResult r = execute(factory.apply(deps));

		assertTrue(r.success(), r.message());
		assertEquals("任务正在执行中，跳过", r.message());
		assertEquals(0, deps.tenantListCalls.get(), "锁被占时不该开始扫租户");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("jobs")
	@DisplayName("锁空闲：拿锁 lock:job:{任务名}、执行本体、结束释放")
	void jobRunsUnderItsOwnLock(String lockName, Function<Deps, Object> factory) {
		Deps deps = new Deps(redis.newInstance());

		JobResult r = execute(factory.apply(deps));

		assertTrue(r.success(), r.message());
		assertEquals(1, deps.tenantListCalls.get(), "拿到锁后应进入本体");
		assertEquals(List.of(JobLock.KEY_PREFIX + lockName), redis.acquired);
		assertTrue(redis.isFree(lockName));
	}

	/** 本体只走到「列租户」：租户列表为空即返回，其余依赖用不到。 */
	static final class Deps {

		final JobLock lock;

		final AtomicInteger tenantListCalls = new AtomicInteger();

		final TenantApi tenantApi = (TenantApi) Proxy.newProxyInstance(JobLockTest.class.getClassLoader(),
				new Class<?>[] { TenantApi.class }, (proxy, method, args) -> {
					if ("list".equals(method.getName())) {
						tenantListCalls.incrementAndGet();
						return R.ok(List.of());
					}
					throw new UnsupportedOperationException(method.getName());
				});

		Deps(JobLock lock) {
			this.lock = lock;
		}
	}

	private static JobResult execute(Object job) {
		return switch (job) {
			case OrderAutoCancelJob j -> j.execute();
			case OrderAutoReceiveJob j -> j.execute();
			case PayOrderReconcileJob j -> j.execute();
			case StockHoldExpiryJob j -> j.execute();
			default -> throw new IllegalArgumentException(job.getClass().getName());
		};
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
