package com.zhiflow.mall.common.trade.statemachine.payorder;

import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.trade.statemachine.Guard;
import com.zhiflow.mall.common.trade.statemachine.Operator;
import com.zhiflow.mall.common.trade.statemachine.TransitionTable;

import static com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderEvent.*;
import static com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderState.*;

/**
 * 支付单跳转表（P2–P9；P1 是建单不是跳转）。表是唯一真相：执行器、穷举测试、文档对照都读它。
 *
 * <p>与 v1.2 字面的两处落地取舍（IMPLEMENTATION D18）：
 * <ul>
 *   <li>P7 不带「同业务单无其他 PAID」守卫：FAILED 单迟到到账而别的单已付时，P9 的起点里没有 FAILED，
 *       若 P7 也拒，这笔钱就无处落。P7 放行后订单已不是待付款 → 调用方写差错池 LATE_PAID，钱照样有落点。</li>
 *   <li>已付类状态再收到确认收款不进表：渠道流水号相同 = 重复回调（NOOP），不同 = 渠道侧重复扣款（REJECTED，
 * 调用方写差错池 DUP_CHARGE）——这个判定要看流水号，放在执行器里做（第⑨步）。</li>
 * </ul>
 */
public final class PayOrderTransitions {

	private PayOrderTransitions() {
	}

	static final Guard<PayOrder, PayOrderTransitionContext> NO_OTHER_PAID = (p, c) -> {
		if (c.otherPaidExists() == null) {
			return "未判定同业务单是否已有 PAID 支付单";
		}
		return c.otherPaidExists() ? "同业务单已有另一张 PAID 支付单（应走 P9）" : null;
	};

	static final Guard<PayOrder, PayOrderTransitionContext> OTHER_PAID = (p, c) -> {
		if (c.otherPaidExists() == null) {
			return "未判定同业务单是否已有 PAID 支付单";
		}
		return c.otherPaidExists() ? null : "同业务单没有别的 PAID 支付单（应走 P4 / P7）";
	};

	public static final TransitionTable<PayOrderState, PayOrderEvent, PayOrder, PayOrderTransitionContext> TABLE =
			TransitionTable.<PayOrderState, PayOrderEvent, PayOrder, PayOrderTransitionContext>builder(
					PayOrderState.class, PayOrderEvent.class)
					.rule("P2").from(CREATED).on(CHANNEL_ORDERED).to(PAYING)
							.by(Operator.USER, Operator.SYSTEM).add()
					.rule("P3").from(CREATED).on(CHANNEL_REJECTED).to(FAILED)
							.by(Operator.USER, Operator.SYSTEM).add()
					.rule("P4").from(CREATED, PAYING).on(PAY_CONFIRMED).to(PAID)
							.when(NO_OTHER_PAID).by(Operator.CHANNEL, Operator.SYSTEM).add()
					.rule("P5").from(PAYING).on(CHANNEL_FAILED).to(FAILED)
							.by(Operator.CHANNEL, Operator.SYSTEM).add()
					.rule("P6").from(CREATED, PAYING).on(ORDER_CLOSED).to(CANCELLED)
							.by(Operator.USER, Operator.SYSTEM, Operator.ADMIN).add()
					.rule("P7").from(FAILED, CANCELLED).on(LATE_PAY_CONFIRMED).to(PAID)
							.by(Operator.CHANNEL, Operator.SYSTEM).add()
					.rule("P9").from(CREATED, PAYING, CANCELLED).on(DUPLICATE_PAY_CONFIRMED).to(PAID)
							.when(OTHER_PAID).by(Operator.CHANNEL, Operator.SYSTEM).add()
					// 重复到达：什么都不做
					.idempotent(PAYING, CHANNEL_ORDERED)
					.idempotent(FAILED, CHANNEL_REJECTED)
					.idempotent(FAILED, CHANNEL_FAILED)
					.idempotent(CANCELLED, CHANNEL_FAILED)
					.idempotent(CANCELLED, ORDER_CLOSED)
					.idempotent(FAILED, ORDER_CLOSED)
					.build();

	static long amountMinor(PayOrder p) {
		if (p.getAmountMinor() != null) {
			return p.getAmountMinor();
		}
		return p.getAmount() == null ? 0
				: p.getAmount().movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
	}
}
