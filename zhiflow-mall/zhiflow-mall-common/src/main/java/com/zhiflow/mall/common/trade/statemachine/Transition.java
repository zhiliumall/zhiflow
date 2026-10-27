package com.zhiflow.mall.common.trade.statemachine;

/**
 * 一次 fire 的结论。
 *
 * <ul>
 *   <li>OK：跳转成功，已写流水与事件；</li>
 *   <li>NOOP：重复到达（登记过的幂等组合），什么都没写；</li>
 *   <li>REJECTED：表外组合 / 条件不满足 / 单据不存在，什么都没写。</li>
 * </ul>
 * 并发冲突（条件更新 0 行）不走这里，抛 {@link ConcurrentTransitionException} 让事务回滚、调用方重读重判。
 */
public record Transition<S>(Outcome outcome, S from, S to, String ruleId, String logId, RejectKind rejectKind,
		String reason) {

	public enum Outcome {
		OK, NOOP, REJECTED
	}

	public enum RejectKind {
		/** 表外组合（非法跳转） */
		NO_RULE,
		/** 规则存在但条件不满足 */
		GUARD,
		/** 单据不存在 */
		NOT_FOUND
	}

	public static <S> Transition<S> ok(S from, S to, String ruleId, String logId) {
		return new Transition<>(Outcome.OK, from, to, ruleId, logId, null, null);
	}

	public static <S> Transition<S> noop(S from) {
		return new Transition<>(Outcome.NOOP, from, from, null, null, null, null);
	}

	public static <S> Transition<S> rejected(S from, RejectKind kind, String reason) {
		return new Transition<>(Outcome.REJECTED, from, null, null, null, kind, reason);
	}

	public boolean isOk() {
		return outcome == Outcome.OK;
	}

	public boolean isNoop() {
		return outcome == Outcome.NOOP;
	}

	public boolean isRejected() {
		return outcome == Outcome.REJECTED;
	}
}
