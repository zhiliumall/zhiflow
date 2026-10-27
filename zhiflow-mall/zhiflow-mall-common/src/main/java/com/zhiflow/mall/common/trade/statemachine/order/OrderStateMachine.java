package com.zhiflow.mall.common.trade.statemachine.order;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderStateLog;
import com.zhiflow.mall.common.trade.statemachine.ConcurrentTransitionException;
import com.zhiflow.mall.common.trade.statemachine.LockRunner;
import com.zhiflow.mall.common.trade.statemachine.Operator;
import com.zhiflow.mall.common.trade.statemachine.Rule;
import com.zhiflow.mall.common.trade.statemachine.Transition;
import com.zhiflow.mall.common.trade.statemachine.TransitionTable;
import com.zhiflow.mall.common.trade.statemachine.TxRunner;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * 订单状态机——<b>订单状态的唯一写入口</b>。
 *
 * <pre>
 * fire = 锁 lock:order:{id}（与 @DistributedLock(name="order") 同键，同线程可重入）
 *      → 事务（REQUIRED，调用方有事务就加入，副作用与状态变更同成同败）
 *      → 锁内 SELECT … FOR UPDATE 重读 → 查表 → 触发方 → 守卫 → 目标
 *      → 条件 UPDATE（原值 IS NOT DISTINCT FROM，带 PG 触发器通行证）→ 流水 → outbox 事件
 * </pre>
 * 表外组合不抛异常：返回 REJECTED 并计数（{@code order_transition_rejected_total}），调用方决定怎么回用户；
 * 条件 UPDATE 0 行抛 {@link ConcurrentTransitionException}，事务回滚。
 */
@Slf4j
public class OrderStateMachine {

	public static final Duration LOCK_WAIT = Duration.ofSeconds(10);

	/** 取消（O5）跳完，商品项全部置 CANCELLED。 */
	static final Set<String> ITEMS_CANCELLED_RULES = Set.of("O5");

	private final OrderStatePort port;

	private final LockRunner lockRunner;

	private final TxRunner txRunner;

	private final TransitionTable<OrderState, OrderEvent, OrderInfo, OrderTransitionContext> table;

	public OrderStateMachine(OrderStatePort port, LockRunner lockRunner, TxRunner txRunner) {
		this(port, lockRunner, txRunner, OrderTransitions.TABLE);
	}

	/** 测试用：换一张表跑同一个执行器（14.8「表与测试同源」的元测试）。 */
	OrderStateMachine(OrderStatePort port, LockRunner lockRunner, TxRunner txRunner,
			TransitionTable<OrderState, OrderEvent, OrderInfo, OrderTransitionContext> table) {
		this.port = port;
		this.lockRunner = lockRunner;
		this.txRunner = txRunner;
		this.table = table;
	}

	public TransitionTable<OrderState, OrderEvent, OrderInfo, OrderTransitionContext> table() {
		return table;
	}

	public static String lockKey(String orderId) {
		return "order:" + orderId;
	}

	public Transition<OrderState> fire(String orderId, OrderEvent event, OrderTransitionContext ctx) {
		return lockRunner.withLock(lockKey(orderId), LOCK_WAIT,
				() -> txRunner.inTx(() -> doFire(orderId, event, ctx)));
	}

	Transition<OrderState> doFire(String orderId, OrderEvent event, OrderTransitionContext ctx) {
		OrderInfo o = port.loadForUpdate(orderId);
		if (o == null) {
			return reject(orderId, null, event, Transition.RejectKind.NOT_FOUND, "订单不存在");
		}
		OrderState from = OrderState.of(o.getStatus(), o.getIsPay());
		Rule<OrderState, OrderEvent, OrderInfo, OrderTransitionContext> rule = table.find(from, event);
		if (rule == null) {
			if (table.isIdempotentRepeat(from, event)) {
				Metrics.counter("order_transition_total", "event", event.name(), "outcome", "NOOP").increment();
				log.info("【状态机】订单 {} 在 {} 收到重复的 {}，无操作", orderId, from, event);
				return Transition.noop(from);
			}
			return reject(orderId, from, event, Transition.RejectKind.NO_RULE,
					"订单当前状态（" + from + "）不允许 " + event);
		}
		Operator operator = ctx.operatorType() == null ? Operator.SYSTEM : ctx.operatorType();
		if (!rule.operators().contains(operator)) {
			return reject(orderId, from, event, Transition.RejectKind.GUARD,
					operator + " 不能触发 " + event + "（规则 " + rule.id() + "）");
		}
		String denied = rule.guard().check(o, ctx);
		if (denied != null) {
			return reject(orderId, from, event, Transition.RejectKind.GUARD, denied);
		}
		OrderState to = rule.target().resolve(o, ctx);
		if (to == null || to == OrderState.LEGACY_INVALID) {
			return reject(orderId, from, event, Transition.RejectKind.GUARD, "目标状态非法：" + to);
		}
		int n = port.compareAndSet(orderId, o.getStatus(), o.getIsPay(), to, ctx.patchOrEmpty());
		if (n == 0) {
			throw new ConcurrentTransitionException("订单 " + orderId + " 在 " + from + " --" + event
					+ "--> " + to + " 时被并发修改");
		}
		if (ITEMS_CANCELLED_RULES.contains(rule.id())) {
			port.markItemsCancelled(orderId);
		}
		OrderStateLog row = new OrderStateLog();
		row.setId(port.nextId());
		row.setOrderId(orderId);
		row.setFromStatus(from.name());
		row.setToStatus(to.name());
		row.setEvent(event.name());
		row.setRuleId(rule.id());
		row.setOperatorType(operator.name());
		row.setOperatorId(ctx.operatorId());
		row.setReason(ctx.reason() == null ? null : truncate(ctx.reason(), 255));
		row.setRequestId(ctx.requestId());
		row.setTenantId(o.getTenantId() == null ? "-" : o.getTenantId());
		port.appendLog(row);
		port.publish(new OrderTransitionedEvent(row.getId(), OrderTransitionedEvent.TYPE, 1, o.getTenantId(),
				OffsetDateTime.now(), orderId, o.getOrderNo(), null, from.name(), to.name(), event.name(),
				rule.id(), operator.name(), ctx.operatorId(), OrderTransitionedEvent.amountMinor(o.getPaymentPrice()),
				"CNY", ctx.requestId()));
		Metrics.counter("order_transition_total", "event", event.name(), "outcome", "OK").increment();
		log.info("【状态机】订单 {} {} --{}--> {}（规则 {}，{}，流水 {}）", orderId, from, event, to, rule.id(),
				operator, row.getId());
		return Transition.ok(from, to, rule.id(), row.getId());
	}

	private Transition<OrderState> reject(String orderId, OrderState from, OrderEvent event,
			Transition.RejectKind kind, String reason) {
		Metrics.counter("order_transition_rejected_total", "from", String.valueOf(from), "event", event.name(),
				"kind", kind.name()).increment();
		log.warn("【状态机】订单 {} 拒绝 {} --{}--> ：{}（{}）", orderId, from, event, reason, kind);
		return Transition.rejected(from, kind, reason);
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}
}
