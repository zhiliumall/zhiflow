package com.zhiflow.mall.api.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 定时任务的多实例互斥：每个实例的 {@code @Scheduled} 都会到点触发，拿到锁的那个执行，其余本轮跳过。
 *
 * <p>用 Redisson 的锁（与 {@code @DistributedLock} 同一套）：
 * <ul>
 *   <li>{@code tryLock()} 不等待：拿不到就是别的实例在跑，本轮记「跳过」，不算失败；</li>
 *   <li>不设租期、走看门狗续期：任务跑多久锁就续多久，不会跑到一半过期被别的实例接手；
 *       实例崩溃后看门狗停了，锁在 30 秒内自动过期；</li>
 *   <li>只释放本线程持有的锁：不会删掉别的实例的锁。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobLock {

	static final String KEY_PREFIX = "lock:job:";

	private final RedissonClient redissonClient;

	/**
	 * 拿到锁才执行 {@code body}。
	 *
	 * @param job  任务名（锁键 {@code lock:job:{job}}）
	 * @param body 任务本体
	 * @return 本体的结论；锁被别的实例占着时返回成功的「跳过」；Redis 不可用时返回失败（本轮没跑）
	 */
	public JobResult runExclusive(String job, Supplier<JobResult> body) {
		RLock lock = redissonClient.getLock(KEY_PREFIX + job);
		boolean acquired;
		try {
			acquired = lock.tryLock();
		}
		catch (RuntimeException e) {
			log.error("{} 任务锁不可用，本轮不执行", job, e);
			return JobResult.failed("任务锁不可用：" + e.getMessage());
		}
		if (!acquired) {
			log.info("{} 正在别的实例执行，本轮跳过", job);
			return JobResult.ok("任务正在执行中，跳过");
		}
		try {
			return body.get();
		}
		finally {
			if (lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		}
	}
}
