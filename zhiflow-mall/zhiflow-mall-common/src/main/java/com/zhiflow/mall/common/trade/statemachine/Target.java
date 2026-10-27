package com.zhiflow.mall.common.trade.statemachine;

/** 目标状态解析（大多数规则是固定目标；支付成功这类按单据属性选目标）。 */
@FunctionalInterface
public interface Target<S, T, C> {

	S resolve(T subject, C ctx);
}
