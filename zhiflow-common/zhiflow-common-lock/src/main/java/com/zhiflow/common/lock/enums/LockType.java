package com.zhiflow.common.lock.enums;

/**
 * 锁类型
 *
 * @date 2025-12-22
 */
public enum LockType {

	/**
	 * 可重入锁（默认）
	 */
	REENTRANT,

	/**
	 * 公平锁
	 */
	FAIR,

	/**
	 * 读锁
	 */
	READ,

	/**
	 * 写锁
	 */
	WRITE
}
