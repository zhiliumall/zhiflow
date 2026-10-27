package com.zhiflow.mall.common.trade.statemachine;

import java.util.Set;

/**
 * 跳转表的一行（from / on / to / when / by 的 DSL 式建表，无状态、全局复用）。
 *
 * @param id               规则编号（O2、P4、R5 …），写进流水，出问题一眼看出是哪条规则放行的
 * @param from             允许的原状态
 * @param event            事件
 * @param target           目标状态解析
 * @param possibleTargets  目标状态的全集（穷举测试据此断言，14.8）
 * @param guard            条件
 * @param operators        允许的触发方（14.2 操作下发据此计算按钮）
 */
public record Rule<S, E, T, C>(String id, Set<S> from, E event, Target<S, T, C> target, Set<S> possibleTargets,
		Guard<T, C> guard, Set<Operator> operators) {
}
