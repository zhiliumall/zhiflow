package com.zhiflow.common.lock.enums;

/**
 * 锁超时策略
 *
 * @date 2025-12-22
 */
public enum LockTimeoutStrategy {

	/**
	 * 快速失败 - 获取不到锁立即抛出异常（兼容 Klock 的 FAIL_FAST）
	 */
	FAIL_FAST,

	/**
	 * 阻塞等待 - 一直等待直到获取到锁
	 */
	BLOCK,

	/**
	 * 超时等待 - 等待指定时间后失败
	 */
	TIMEOUT_WAIT,

	/**
	 * 静默失败 - 获取不到锁返回 null（适用于非关键操作）
	 */
	SILENT_FAIL
}
