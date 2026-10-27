package com.zhiflow.common.data.tenant;

import com.alibaba.ttl.TtlRunnable;
import com.alibaba.ttl.TtlWrappers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/**
 * 投到公共 ForkJoin 池的异步任务，按<b>提交那一刻</b>的上下文（租户、C 端会话等 TTL）执行。
 *
 * <p>为什么不能直接写 {@code CompletableFuture.runAsync(task)}：公共池的线程是复用的，
 * {@code TransmittableThreadLocal} 只在<b>建线程那一刻</b>按 InheritableThreadLocal 语义拷贝一次，
 * 之后池线程一直带着「最早建它的那个请求」的租户。任务里查库落到那个租户的 schema，
 * 内部调用经 {@code TenantRequestInterceptor} 把这个旧租户写进请求头
 * （9/14、9/15 两次支付回调后发通知就是这样查错租户失败的，见 {@code OrderNotificationService}）。
 *
 * <p>这里用 TTL 包装：提交时抓取、执行时回放、执行完还原池线程原来的值 ——
 * 任务里自己 {@code setTenantId} 也不会留在池线程上污染下一个任务。
 * 执行资源仍是公共池（并发上限不变），只修上下文。
 *
 * <p>门禁 {@code CommonPoolAsyncGateTest} 禁止业务代码直接调单参的 {@code runAsync/supplyAsync}。
 */
public final class TenantAwareAsync {

	private TenantAwareAsync() {
	}

	public static CompletableFuture<Void> runAsync(Runnable task) {
		return CompletableFuture.runAsync(TtlRunnable.get(task, false, true), ForkJoinPool.commonPool());
	}

	public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
		return CompletableFuture.supplyAsync(TtlWrappers.wrapSupplier(supplier), ForkJoinPool.commonPool());
	}

}
