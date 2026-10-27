package com.zhiflow.mall.api.job;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用的「一个 Redis」：同一个实例造出来的多个 {@link JobLock} 共享同一组锁，模拟多个应用实例连同一个 Redis。
 *
 * <p>只实现 {@link JobLock} 用到的 {@code getLock} / {@code tryLock()} / {@code isHeldByCurrentThread} / {@code unlock}，
 * 语义照 Redisson：锁归属线程，只有持有线程能释放。用 JDK 动态代理而不是 Mockito（不 mock 具体类）。
 */
final class InMemoryLocks {

	private final Map<String, AtomicReference<Thread>> owners = new ConcurrentHashMap<>();

	/** 按顺序记录拿到过的锁键。 */
	final List<String> acquired = new CopyOnWriteArrayList<>();

	/** 置 true 后 {@code tryLock} 抛异常，模拟 Redis 不可用。 */
	volatile boolean broken;

	/** 一个连到这个「Redis」的应用实例。 */
	JobLock newInstance() {
		RedissonClient client = (RedissonClient) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { RedissonClient.class }, (proxy, method, args) -> {
					if ("getLock".equals(method.getName())) {
						return lock((String) args[0]);
					}
					throw new UnsupportedOperationException(method.getName());
				});
		return new JobLock(client);
	}

	/** 让「别的实例」占住这把锁（用另一个线程持有，当前线程拿不到也放不掉）。 */
	void holdByAnotherInstance(String job) {
		owners.computeIfAbsent(JobLock.KEY_PREFIX + job, k -> new AtomicReference<>()).set(new Thread());
	}

	boolean isFree(String job) {
		AtomicReference<Thread> owner = owners.get(JobLock.KEY_PREFIX + job);
		return owner == null || owner.get() == null;
	}

	private RLock lock(String key) {
		AtomicReference<Thread> owner = owners.computeIfAbsent(key, k -> new AtomicReference<>());
		return (RLock) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { RLock.class },
				(proxy, method, args) -> switch (method.getName()) {
					case "tryLock" -> {
						if (args != null && args.length > 0) {
							throw new UnsupportedOperationException("JobLock 只该用不等待、不设租期的 tryLock()");
						}
						if (broken) {
							throw new IllegalStateException("redis down");
						}
						boolean ok = owner.compareAndSet(null, Thread.currentThread());
						if (ok) {
							acquired.add(key);
						}
						yield ok;
					}
					case "isHeldByCurrentThread" -> owner.get() == Thread.currentThread();
					case "unlock" -> {
						if (!owner.compareAndSet(Thread.currentThread(), null)) {
							throw new IllegalMonitorStateException("不是本线程持有的锁: " + key);
						}
						yield null;
					}
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					case "toString" -> "InMemoryLock(" + key + ")";
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
