package com.zhiflow.common.lock.annotation;

import com.zhiflow.common.lock.enums.LockTimeoutStrategy;
import com.zhiflow.common.lock.enums.LockType;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解（兼容 Klock）
 *
 * @date 2025-12-22
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

	/**
	 * 锁名称（前缀）
	 */
	String name() default "";

	/**
	 * 锁键，支持 SpEL 表达式
	 * 例如：{"#userId"} 或 {"#entity.getId()"}
	 */
	String[] keys() default {};

	/**
	 * 锁类型：可重入锁、公平锁、读写锁等
	 */
	LockType lockType() default LockType.REENTRANT;

	/**
	 * 获取锁超时时间
	 */
	long waitTime() default 5;

	/**
	 * 锁自动释放时间（看门狗机制下默认 30s，可自定义）
	 * -1 表示启用看门狗机制（自动续期）
	 */
	long leaseTime() default -1;

	/**
	 * 时间单位
	 */
	TimeUnit timeUnit() default TimeUnit.SECONDS;

	/**
	 * 获取锁超时策略
	 */
	LockTimeoutStrategy lockTimeoutStrategy() default LockTimeoutStrategy.FAIL_FAST;

	/**
	 * 自定义业务键分隔符
	 */
	String keySeparator() default ":";
}
