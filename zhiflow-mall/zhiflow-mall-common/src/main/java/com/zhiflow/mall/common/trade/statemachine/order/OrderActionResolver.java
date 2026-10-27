package com.zhiflow.mall.common.trade.statemachine.order;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.trade.statemachine.Operator;
import com.zhiflow.mall.common.trade.statemachine.Rule;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 订单操作服务端下发：前端和后台的每一个按钮都由跳转表算出来，表改了按钮自动跟着改。
 *
 * <p>计算：遍历 {@link OrderTransitions#TABLE} 中从当前状态出发、触发方包含买家（或后台）的跳转，调用守卫的「轻校验」
 * 得出 enabled——不读渠道、不锁行。不可用的操作也返回（带原因），前端置灰。另有两个不是跳转的操作（付款、删除）
 * 按同一口径在这里算。
 *
 * <p><b>这只是展示建议</b>：每个操作接口依旧走状态机 {@code fire()}，两者同源于同一张表，所以不会不一致。
 */
public final class OrderActionResolver {

	private OrderActionResolver() {
	}

	/** 一个操作：code 给前端判断，label 给按钮文字，disabledReason 给置灰提示。 */
	public record ActionView(String code, String label, boolean enabled, String disabledReason) {

		static ActionView on(String code, String label) {
			return new ActionView(code, label, true, null);
		}

		static ActionView off(String code, String label, String why) {
			return new ActionView(code, label, false, why);
		}
	}

	/** 谁在看。 */
	public enum Side {
		USER(Operator.USER), ADMIN(Operator.ADMIN);

		final Operator operator;

		Side(Operator operator) {
			this.operator = operator;
		}
	}

	/**
	 * 计算用的额外事实（都由调用方从已查到的数据里给，不在这里查库）。
	 *
	 * @param payRemainSeconds  待付款还剩多少秒可付（null = 不适用）
	 */
	public record Facts(Long payRemainSeconds) {

		public static final Facts NONE = new Facts(null);
	}

	private static final Map<OrderEvent, String[]> USER_EVENTS = new EnumMap<>(OrderEvent.class);

	private static final Map<OrderEvent, String[]> ADMIN_EVENTS = new EnumMap<>(OrderEvent.class);

	static {
		USER_EVENTS.put(OrderEvent.CANCEL, new String[]{"CAN_CANCEL", "取消订单"});
		USER_EVENTS.put(OrderEvent.RECEIVE, new String[]{"CAN_CONFIRM", "确认收货"});
		ADMIN_EVENTS.put(OrderEvent.CANCEL, new String[]{"ADMIN_CANCEL", "取消订单"});
		ADMIN_EVENTS.put(OrderEvent.SHIP, new String[]{"ADMIN_SHIP", "发货"});
		ADMIN_EVENTS.put(OrderEvent.RECEIVE, new String[]{"ADMIN_RECEIVE", "确认收货"});
		ADMIN_EVENTS.put(OrderEvent.MANUAL_FIX, new String[]{"ADMIN_MANUAL_FIX", "人工处理"});
	}

	public static List<ActionView> resolve(OrderInfo o, Side side, Facts facts, LocalDateTime now) {
		OrderState st = OrderState.of(o.getStatus(), o.getIsPay());
		Facts f = facts == null ? Facts.NONE : facts;
		List<ActionView> out = new ArrayList<>();
		Map<OrderEvent, String[]> labels = side == Side.USER ? USER_EVENTS : ADMIN_EVENTS;
		if (side == Side.USER && st == OrderState.PENDING_PAY) {
			boolean payable = f.payRemainSeconds() == null || f.payRemainSeconds() > 0;
			out.add(payable ? ActionView.on("CAN_PAY", "去支付") : ActionView.off("CAN_PAY", "去支付", "订单已超时，请重新下单"));
		}
		for (Rule<OrderState, OrderEvent, OrderInfo, OrderTransitionContext> rule : OrderTransitions.TABLE.rulesFrom(st,
				side.operator)) {
			String[] label = labels.get(rule.event());
			if (label == null) {
				continue;
			}
			// 轻校验：原因类守卫在展示时不算（按钮点开再填），给一个占位原因；人工处理给一个占位目标
			OrderTransitionContext ctx = OrderTransitionContext.builder().operatorType(side.operator).reason("展示")
					.manualTarget(OrderState.PENDING_PAY).build();
			String denied = rule.guard().check(o, ctx);
			out.add(denied == null ? ActionView.on(label[0], label[1]) : ActionView.off(label[0], label[1], denied));
		}
		if (side == Side.USER && st == OrderState.CANCELLED) {
			out.add(ActionView.on("CAN_DELETE", "删除订单"));
		}
		return out;
	}
}
