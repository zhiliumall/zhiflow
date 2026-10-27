package com.zhiflow.mall.common.trade.statemachine;

/**
 * 条件更新影响 0 行：锁内重读之后、UPDATE 之前单据被别人改了（锁失效或绕过锁的写）。
 * 抛出让事务整体回滚，调用方重读重判——绝不能吞掉继续往下做副作用。
 */
public class ConcurrentTransitionException extends RuntimeException {

	public ConcurrentTransitionException(String message) {
		super(message);
	}
}
