package com.zhiflow.mall.common.trade.statemachine;

/**
 * 跳转条件。返回 null 表示通过，否则返回拒绝原因（给调用方翻译成业务错误）。
 *
 * @param <T> 单据快照（锁内重读）
 * @param <C> 本次跳转的上下文
 */
@FunctionalInterface
public interface Guard<T, C> {

	String check(T subject, C ctx);

	static <T, C> Guard<T, C> none() {
		return (s, c) -> null;
	}
}
