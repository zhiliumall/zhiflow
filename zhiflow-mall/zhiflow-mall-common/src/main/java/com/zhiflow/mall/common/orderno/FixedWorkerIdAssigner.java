package com.zhiflow.mall.common.orderno;

/**
 * 过渡期的机器位实现：返回固定值，默认 {@code (0, 0)}。
 *
 * <p>{@code (0, 0)} 是收口前全仓 22 处 {@code IdUtil.getSnowflake(0,0)} 的原值，
 * 保持不变才能让 T1 是纯重构。
 *
 * <p>⚠️ 已知缺陷，由 T2 修复：mall-api 与 mall-admin 是两个独立 JVM，
 * 都用 {@code (0, 0)}，跨进程无协调，同毫秒同序列即碰撞。
 * 本类不解决该问题，只负责把「机器位从哪来」这件事变成一个可替换的接缝。
 */
public class FixedWorkerIdAssigner implements WorkerIdAssigner {

	private final long workerId;

	private final long dataCenterId;

	public FixedWorkerIdAssigner(long workerId, long dataCenterId) {
		this.workerId = workerId;
		this.dataCenterId = dataCenterId;
	}

	@Override
	public long getWorkerId() {
		return workerId;
	}

	@Override
	public long getDataCenterId() {
		return dataCenterId;
	}
}
