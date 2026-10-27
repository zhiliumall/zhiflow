package com.zhiflow.mall.common.orderno;

import java.time.Duration;

/**
 * 机器位分配策略配置。前缀 {@code orderno}。
 *
 * <p>逃生舱：把 {@code orderno.worker-id-strategy} 置为 {@code fixed} 即退回硬编码
 * {@code (0,0)}，行为与 T2 之前完全一致，**不需要回滚 DDL**（表留着不影响任何链路）。
 */
public class WorkerIdRegistryProperties {

	/** 注册表抢占（默认）。 */
	public static final String STRATEGY_REGISTRY = "registry";

	/** 逃生舱：退回固定机器位。 */
	public static final String STRATEGY_FIXED = "fixed";

	private String workerIdStrategy = STRATEGY_REGISTRY;

	private long fixedWorkerId = 0L;

	private long fixedDataCenterId = 0L;

	/**
	 * 心跳间隔。续约失败不会立刻让本进程失去槽位——要连续失到超过
	 * {@link #expireAfter} 才会被别人抢走。
	 */
	private Duration heartbeatInterval = Duration.ofSeconds(30);

	/**
	 * 槽位过期阈值：心跳早于 now() - expireAfter 的槽位可被抢占。
	 *
	 * <p>必须显著大于一次 Full GC + 网络抖动的时长，否则一次长停顿就会让**存活进程的槽位
	 * 被别人抢走**，于是两个进程用同一机器位——那正是本任务要消除的情形。
	 * 默认 4 倍心跳间隔。
	 */
	private Duration expireAfter = Duration.ofMinutes(2);

	public String getWorkerIdStrategy() {
		return workerIdStrategy;
	}

	public void setWorkerIdStrategy(String workerIdStrategy) {
		this.workerIdStrategy = workerIdStrategy;
	}

	public long getFixedWorkerId() {
		return fixedWorkerId;
	}

	public void setFixedWorkerId(long fixedWorkerId) {
		this.fixedWorkerId = fixedWorkerId;
	}

	public long getFixedDataCenterId() {
		return fixedDataCenterId;
	}

	public void setFixedDataCenterId(long fixedDataCenterId) {
		this.fixedDataCenterId = fixedDataCenterId;
	}

	public Duration getHeartbeatInterval() {
		return heartbeatInterval;
	}

	public void setHeartbeatInterval(Duration heartbeatInterval) {
		this.heartbeatInterval = heartbeatInterval;
	}

	public Duration getExpireAfter() {
		return expireAfter;
	}

	public void setExpireAfter(Duration expireAfter) {
		this.expireAfter = expireAfter;
	}

	public boolean isRegistryStrategy() {
		return !STRATEGY_FIXED.equalsIgnoreCase(workerIdStrategy);
	}
}
