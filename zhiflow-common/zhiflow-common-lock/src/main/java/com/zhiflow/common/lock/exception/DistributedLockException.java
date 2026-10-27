package com.zhiflow.common.lock.exception;

/**
 * 分布式锁异常
 *
 * @date 2025-12-22
 */
public class DistributedLockException extends RuntimeException {

	public DistributedLockException(String message) {
		super(message);
	}

	public DistributedLockException(String message, Throwable cause) {
		super(message, cause);
	}
}
