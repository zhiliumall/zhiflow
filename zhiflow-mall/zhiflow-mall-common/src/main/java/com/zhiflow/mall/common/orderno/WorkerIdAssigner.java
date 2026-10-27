package com.zhiflow.mall.common.orderno;

/**
 * 雪花算法机器位来源。
 *
 * <p>本接口存在的唯一理由是给 T2「机器位唯一化」留替换位：过渡期由
 * {@link FixedWorkerIdAssigner} 返回硬编码的 {@code (0, 0)}，与收口前完全一致；
 * T2 落地后换成启动期动态分配（注册表抢占 / 配置注入 / 运行时推导，路线待定），
 * {@link OrderNoGenerator} 本身不需要任何改动。
 *
 * <p>⚠️ 因此 {@link OrderNoGenerator} 刻意不做成静态工具类——静态方法拿不到启动期
 * 才能确定的 workerId。
 *
 * <p>取值范围由 Hutool {@code Snowflake} 约束：两者均为 5 bit，即 0 ~ 31。
 */
public interface WorkerIdAssigner {

	/**
	 * @return 工作机器 ID，0 ~ 31
	 */
	long getWorkerId();

	/**
	 * @return 数据中心 ID，0 ~ 31
	 */
	long getDataCenterId();
}
