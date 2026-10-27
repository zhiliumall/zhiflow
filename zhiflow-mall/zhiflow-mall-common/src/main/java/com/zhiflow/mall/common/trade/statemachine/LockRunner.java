package com.zhiflow.mall.common.trade.statemachine;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 编程式分布式锁（锁包住事务、提交后才释锁，不靠注解顺序）。
 *
 * <p>实现必须与 {@code @DistributedLock(name = "order", keys = "#id")} 用同一个键
 * （{@code lock:order:{id}}）且可重入：已持锁的调用链（如 notifyOrder）内部再 fire 不会自锁。
 */
public interface LockRunner {

	<T> T withLock(String key, Duration wait, Supplier<T> body);
}
