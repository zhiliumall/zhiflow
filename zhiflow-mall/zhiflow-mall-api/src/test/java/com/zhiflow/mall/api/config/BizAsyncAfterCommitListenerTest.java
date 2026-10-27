package com.zhiflow.mall.api.config;

import com.zhiflow.common.data.tenant.TenantContextHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高并发下单那条路径的端到端复现：{@code @Async + @TransactionalEventListener(AFTER_COMMIT)} 监听器
 * （同 {@code OrderEventListener.handleOrderCreated}）在 bizAsyncExecutor 已满时被触发。
 * <p>
 * 旧版 CallerRuns：监听器在发布事件的线程（请求线程）的 {@code triggerAfterCompletion} 里同步执行，
 * 此时 {@code isActualTransactionActive()} 仍为 true、同步回调已清空，「在事务里就挂到提交后执行」的写法注册回调抛异常
 * （当年是消息队列发送；开源分支去掉 MQ 后，探针直接用同一种写法）。
 * 新版：提交方（请求线程）不等，事务收尾立即结束；监听器在自己的虚拟线程里等名额、拿到后执行，看不到请求线程的事务上下文。
 * </p>
 */
class BizAsyncAfterCommitListenerTest {

	@AfterEach
	void clearTenant() {
		TenantContextHolder.clear();
	}

	@Test
	@DisplayName("名额占满时 AFTER_COMMIT 监听：请求线程不等、事务照常结束；监听器在虚拟线程里等到名额后执行、不在请求线程的事务里")
	void afterCommitListenerRunsOffRequestThreadWhenExecutorFull() throws Exception {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			// 上限 1：先用一个阻塞任务把名额占满
			ctx.getEnvironment().getPropertySources()
					.addFirst(new MapPropertySource("test", Map.of("zhiflow.biz-async.concurrency-limit", "1")));
			ctx.register(AsyncConfig.class, TestSupportConfig.class, ProbeListener.class);
			ctx.refresh();

			AsyncConfig.BizAsyncTaskExecutor executor = ctx.getBean("bizAsyncExecutor", AsyncConfig.BizAsyncTaskExecutor.class);
			assertEquals(1, executor.permitLimit());
			ProbeState probe = ctx.getBean(ProbeState.class);
			TransactionTemplate tx = new TransactionTemplate(new NoDbTransactionManager());

			CountDownLatch release = new CountDownLatch(1);
			CountDownLatch blockerStarted = new CountDownLatch(1);
			executor.execute(() -> {
				blockerStarted.countDown();
				try {
					release.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

			try {
				AtomicReference<Throwable> requestFailure = new AtomicReference<>();
				Thread requestThread = Thread.ofPlatform().name("request-thread-sim").start(() -> {
					try {
						TenantContextHolder.setTenantId("tenant-7");
						tx.executeWithoutResult(status -> ctx.publishEvent(new ProbeEvent("order-1")));
					}
					catch (Throwable ex) {
						requestFailure.set(ex);
					}
					finally {
						TenantContextHolder.clear();
					}
				});

				requestThread.join(2_000);
				assertFalse(requestThread.isAlive(), () -> "请求线程不能等名额，应立即结束事务，实际状态=" + requestThread.getState());
				assertNull(requestFailure.get(), () -> "请求线程不应失败：" + requestFailure.get());
				assertTrue(BizAsyncExecutorTest.waitUntil(() -> executor.waitingCount() == 1), "监听器应在自己的虚拟线程里等名额");
				assertEquals(1, probe.done.getCount(), () -> "名额没空出来之前监听器就执行了：执行线程=" + probe.ranOn.get()
						+ "，当时处在事务中=" + probe.actualTxActive.get() + "，同步回调可注册=" + probe.syncActive.get());
				assertEquals(1, executor.throttledCount());

				release.countDown();
				assertTrue(probe.done.await(5, TimeUnit.SECONDS), "放行后监听器必须执行");

				Thread ranOn = probe.ranOn.get();
				assertNotSame(requestThread, ranOn, "监听器不能在请求线程上执行（旧 CallerRuns 的问题）");
				assertTrue(ranOn.isVirtual(), "监听器在 bizAsyncExecutor 的虚拟线程上执行");
				assertTrue(ranOn.getName().startsWith("mall-biz-async-"));
				assertFalse(probe.actualTxActive.get(), "监听器所在线程不能处在请求线程的事务里");
				assertFalse(probe.syncActive.get());
				assertEquals("tenant-7", probe.tenant.get(), "租户上下文按发布事件那一刻传到监听器");
				assertEquals("order-1", probe.payload.get());
				assertNull(probe.sendFailure.get(), () -> "监听器里的提交后动作不能失败：" + probe.sendFailure.get());
			}
			finally {
				release.countDown();
			}
		}
	}

	record ProbeEvent(String orderId) {
	}

	/** 监听器观察到的现场；单独一个对象（ProbeListener 会被 @Async 代理，代理对象上的字段读不到目标的值）。 */
	static final class ProbeState {

		final CountDownLatch done = new CountDownLatch(1);

		final AtomicReference<Thread> ranOn = new AtomicReference<>();

		final AtomicReference<Boolean> actualTxActive = new AtomicReference<>();

		final AtomicReference<Boolean> syncActive = new AtomicReference<>();

		final AtomicReference<String> tenant = new AtomicReference<>();

		final AtomicReference<String> payload = new AtomicReference<>();

		final AtomicReference<Throwable> sendFailure = new AtomicReference<>();
	}

	@Component
	static class ProbeListener {

		private final ProbeState state;

		ProbeListener(ProbeState state) {
			this.state = state;
		}

		@Async
		@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
		public void onOrderCreated(ProbeEvent event) {
			state.ranOn.set(Thread.currentThread());
			state.actualTxActive.set(TransactionSynchronizationManager.isActualTransactionActive());
			state.syncActive.set(TransactionSynchronizationManager.isSynchronizationActive());
			state.tenant.set(TenantContextHolder.getTenantId());
			state.payload.set(event.orderId());
			try {
				// 「在事务里就挂到提交后执行，否则马上执行」——在请求线程的事务收尾阶段跑到这里会抛 IllegalStateException
				if (TransactionSynchronizationManager.isActualTransactionActive()) {
					TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					});
				}
			}
			catch (Throwable ex) {
				state.sendFailure.set(ex);
			}
			state.done.countDown();
		}
	}

	@Configuration
	static class TestSupportConfig {

		@Bean
		static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
			return new PropertySourcesPlaceholderConfigurer();
		}

		/** 让 @TransactionalEventListener 生效（生产里由 @EnableTransactionManagement 注册）。 */
		@Bean
		static TransactionalEventListenerFactory transactionalEventListenerFactory() {
			return new TransactionalEventListenerFactory();
		}

		@Bean
		SimpleMeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}

		@Bean
		ProbeState probeState() {
			return new ProbeState();
		}
	}

	/** 不连库的事务管理器：只为走一遍 Spring 真实的提交 / 同步回调流程。 */
	static final class NoDbTransactionManager extends AbstractPlatformTransactionManager {

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
		}
	}

}
