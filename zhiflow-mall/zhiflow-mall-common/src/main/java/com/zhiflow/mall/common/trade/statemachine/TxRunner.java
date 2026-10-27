package com.zhiflow.mall.common.trade.statemachine;

import java.util.function.Supplier;

/** 事务模板（REQUIRED：调用方已有事务则加入，保持副作用与状态变更原子）。 */
public interface TxRunner {

	<T> T inTx(Supplier<T> body);
}
