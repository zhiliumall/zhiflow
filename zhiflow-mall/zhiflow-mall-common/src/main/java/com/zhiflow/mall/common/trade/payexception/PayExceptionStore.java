package com.zhiflow.mall.common.trade.payexception;

import com.zhiflow.mall.common.entity.PayException;

/**
 * 差错池的持久化端口。
 *
 * <p>mall-common 没有 MyBatis-Spring 的扫描能力，且两个服务的 {@code @MapperScan}
 * 刻意不扫本模块（改扫描范围会影响跨服务同名组件的装配顺序，见 {@code OrderNoGeneratorAutoConfiguration}）。
 * 所以 mapper 由 mall-api / mall-admin 各自在自己的 mapper 包里声明，并实现本接口。
 */
public interface PayExceptionStore {

	/**
	 * {@code INSERT … ON CONFLICT (dedupe_key) DO NOTHING}。
	 *
	 * @return 1 = 新入池；0 = 同一笔钱此前已入池
	 */
	int insertIfAbsent(PayException row);
}
