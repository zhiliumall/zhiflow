package com.zhiflow.common.data.cache;

/**
 * 进程内 L1（{@link TwoLevelRedisCache}）按「近似常驻 KB」计权的缓存值。
 *
 * <p>只对策略是按 KB 计权的缓存名生效（见 {@link RedisL1Settings#ALLOWED}）。方法名刻意不是 getter：
 * 缓存值要经 Jackson 写进 Redis，{@code getXxx} 会被当成字段序列化出去。
 */
public interface L1Weighted {

	/** 近似常驻内存（KB，至少 1）。宁可估大：估小了 L1 会多占堆。 */
	int l1WeightKb();

}
