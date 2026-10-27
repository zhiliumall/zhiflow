package com.zhiflow.common.lock.aspect;

import com.zhiflow.common.lock.annotation.DistributedLock;
import com.zhiflow.common.lock.enums.LockTimeoutStrategy;
import com.zhiflow.common.lock.enums.LockType;
import com.zhiflow.common.lock.exception.DistributedLockException;
import com.zhiflow.common.lock.parser.SpELParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁 AOP 切面
 *
 * <h2>为什么必须显式声明 {@code @Order(HIGHEST_PRECEDENCE)}（D66）</h2>
 * <p>本切面几乎总是和 {@code @Transactional} 叠在同一个方法上（见 {@code OrderInfoServiceImpl}
 * 的 notifyOrder / orderCancel / balancePay）。两者的内外层顺序由 advisor 排序决定：
 * <ul>
 *   <li><b>锁在外（本注解声明的顺序）</b>：加锁 → 开事务 → 业务 → <b>提交</b> → 解锁。
 *       其它线程拿到锁时，前一个事务已经提交，锁内重查 DB 读到的是最新状态，幂等判据成立。</li>
 *   <li><b>事务在外（不声明 @Order 时可能出现的顺序）</b>：开事务 → 加锁 → 业务 → 解锁 → <b>提交</b>。
 *       锁在提交<b>之前</b>就放了，留下一个窗口：T1 解锁但未提交，T2 立刻拿到锁、
 *       重查 DB 读到的仍是 T1 提交前的旧状态，于是判定"还没处理过"，整套副作用再来一遍
 *       —— 积分发两次、销量加两次、OrderPaidEvent 发两次（佣金跟着发两次）。</li>
 * </ul>
 * <p>Spring 的事务 advisor 默认是 {@code LOWEST_PRECEDENCE}；本切面此前<b>没有</b> {@code @Order}，
 * 同样是 {@code LOWEST_PRECEDENCE} —— 两者打平。实测（{@code DistributedLockTransactionOrderTest}
 * 去掉本注解后运行）当前 Spring 版本上事件顺序是
 * {@code [tx:begin, lock:acquire, biz, lock:release, tx:commit]}，
 * <b>正是危险的那一种：事务在外、锁在提交前就释放</b>。不是理论风险，是现状。
 *
 * <h2>为什么不是 {@code Ordered.HIGHEST_PRECEDENCE}</h2>
 * <p>{@code HIGHEST_PRECEDENCE}（{@code Integer.MIN_VALUE}）会排到
 * {@code ExposeInvocationInterceptor.ADVISOR}（order = {@code MIN_VALUE + 1}）<b>前面</b>。
 * 那个 interceptor 正是 AspectJ 运行时参数绑定的载体：本切面的
 * {@code @Around("@annotation(distributedLock)")} 要靠它把 {@code JoinPointMatch} 传进来。
 * 排到它前面，每次调用都会直接抛
 * {@code IllegalStateException: Required to bind 2 arguments, but only bound 1
 * (JoinPointMatch was NOT bound in invocation)} ——
 * <b>所有 @DistributedLock 方法当场全挂</b>（已实测复现）。
 * 所以取 {@code HIGHEST_PRECEDENCE + 10}：足够压过事务 advisor，又不越过 ExposeInvocationInterceptor。
 *
 * <p>配套的第二道防线在业务侧：关键状态翻转（如 {@code order_info.is_pay}）改用
 * CAS（{@code WHERE is_pay='0'}）而不是全字段 {@code updateById}，
 * 这样即使将来有人把切面顺序改坏，DB 也只会让一个事务成功。
 *
 * @date 2025-12-22
 */
@Slf4j
@Aspect
@Component
@Order(DistributedLockAspect.LOCK_ASPECT_ORDER)
@RequiredArgsConstructor
public class DistributedLockAspect {

	/**
	 * 切面优先级：必须压过事务 advisor（默认 {@code LOWEST_PRECEDENCE}），
	 * 但<b>不能</b>越过 {@code ExposeInvocationInterceptor.ADVISOR}（{@code MIN_VALUE + 1}）——
	 * 越过它 AspectJ 的参数绑定就断了，见类注释。留 10 的余量给将来可能插在中间的切面。
	 */
	public static final int LOCK_ASPECT_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

	private final RedissonClient redissonClient;

	@Around("@annotation(distributedLock)")
	public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
		// 1. 解析锁键
		String lockKey = buildLockKey(distributedLock, joinPoint);

		// 2. 获取锁实例
		RLock lock = getLock(lockKey, distributedLock.lockType());

		// 3. 根据策略尝试获取锁
		boolean acquired = tryAcquireLock(lock, distributedLock);

		if (!acquired) {
			return handleLockFailure(distributedLock.lockTimeoutStrategy(), lockKey);
		}

		// 4. 执行业务逻辑
		try {
			log.debug("成功获取分布式锁: {}", lockKey);
			return joinPoint.proceed();
		}
		finally {
			// 5. 释放锁
			releaseLock(lock, lockKey);
		}
	}

	/**
	 * 构建锁键
	 */
	private String buildLockKey(DistributedLock lock, ProceedingJoinPoint joinPoint) {
		String prefix = lock.name();
		String keys = SpELParser.parseKeys(lock.keys(), joinPoint, lock.keySeparator());

		if (keys.isEmpty()) {
			return "lock:" + prefix;
		}
		return "lock:" + prefix + ":" + keys;
	}

	/**
	 * 根据锁类型获取锁实例
	 */
	private RLock getLock(String lockKey, LockType lockType) {
		return switch (lockType) {
			case REENTRANT -> redissonClient.getLock(lockKey);
			case FAIR -> redissonClient.getFairLock(lockKey);
			case READ -> redissonClient.getReadWriteLock(lockKey).readLock();
			case WRITE -> redissonClient.getReadWriteLock(lockKey).writeLock();
		};
	}

	/**
	 * 尝试获取锁
	 */
	private boolean tryAcquireLock(RLock lock, DistributedLock distributedLock) throws InterruptedException {
		long waitTime = distributedLock.waitTime();
		long leaseTime = distributedLock.leaseTime();
		TimeUnit timeUnit = distributedLock.timeUnit();

		// leaseTime = -1 时启用看门狗机制
		if (leaseTime == -1) {
			if (distributedLock.lockTimeoutStrategy() == LockTimeoutStrategy.FAIL_FAST) {
				return lock.tryLock(0, timeUnit);
			}
			return lock.tryLock(waitTime, timeUnit);
		}

		return lock.tryLock(waitTime, leaseTime, timeUnit);
	}

	/**
	 * 处理获取锁失败的情况
	 */
	private Object handleLockFailure(LockTimeoutStrategy strategy, String lockKey) {
		return switch (strategy) {
			case FAIL_FAST -> throw new DistributedLockException("获取分布式锁失败: " + lockKey);
			case SILENT_FAIL -> {
				log.warn("获取分布式锁失败（静默失败）: {}", lockKey);
				yield null;
			}
			default -> throw new DistributedLockException("不支持的锁超时策略: " + strategy);
		};
	}

	/**
	 * 释放锁
	 */
	private void releaseLock(RLock lock, String lockKey) {
		if (lock.isHeldByCurrentThread()) {
			lock.unlock();
			log.debug("释放分布式锁: {}", lockKey);
		}
	}

}
