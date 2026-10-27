package com.zhiflow.mall.common.trade.statemachine.payorder;

import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.entity.PayOrderStateLog;
import com.zhiflow.mall.common.trade.statemachine.ConcurrentTransitionException;
import com.zhiflow.mall.common.trade.statemachine.LockRunner;
import com.zhiflow.mall.common.trade.statemachine.Operator;
import com.zhiflow.mall.common.trade.statemachine.Rule;
import com.zhiflow.mall.common.trade.statemachine.Transition;
import com.zhiflow.mall.common.trade.statemachine.TransitionTable;
import com.zhiflow.mall.common.trade.statemachine.TxRunner;
import com.zhiflow.mall.common.trade.statemachine.order.OrderTransitionedEvent;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 支付单状态机——<b>pay_order.status 的唯一写入口</b>（v1.2 三章 / 7.1）。
 *
 * <pre>
 * fire = 锁 lock:payorder:{payNo} → 事务（REQUIRED）→ 锁内 SELECT … FOR UPDATE 重读 → 查表 → 触发方 → 守卫 → 目标
 *      → 条件 UPDATE（原状态，带 PG 触发器通行证）→ pay_order_state_log → outbox 事件
 * </pre>
 * <b>调用方不得在 fire 之内（持锁 / 事务中）做外部 HTTP 调用</b>：渠道回查、下单、退款提交都在 fire 之前或之后。
 *
 * <p>已付类状态（PAID / PARTIAL_REFUNDED / REFUNDED）再收到确认收款：流水号相同或未给 → NOOP（重复回调）；
 * 流水号不同 → REJECTED，原因以 {@link #DUP_CHARGE} 开头，调用方写差错池 DUP_CHARGE（第⑨步）。
 */
@Slf4j
public class PayOrderStateMachine {

	public static final Duration LOCK_WAIT = Duration.ofSeconds(10);

	/** 事件类型（outbox / 14.9 标准格式的 eventType）。 */
	public static final String EVENT_TYPE = "PayOrderTransitioned";

	/** 拒绝原因前缀：已付单收到不同渠道流水号的确认收款。 */
	public static final String DUP_CHARGE = "DUP_CHARGE";

	private static final Set<PayOrderEvent> CONFIRMS = EnumSet.of(PayOrderEvent.PAY_CONFIRMED,
			PayOrderEvent.LATE_PAY_CONFIRMED, PayOrderEvent.DUPLICATE_PAY_CONFIRMED);

	private static final Set<PayOrderState> PAID_LIKE = EnumSet.of(PayOrderState.PAID);

	private final PayOrderStatePort port;

	private final LockRunner lockRunner;

	private final TxRunner txRunner;

	private final TransitionTable<PayOrderState, PayOrderEvent, PayOrder, PayOrderTransitionContext> table;

	public PayOrderStateMachine(PayOrderStatePort port, LockRunner lockRunner, TxRunner txRunner) {
		this(port, lockRunner, txRunner, PayOrderTransitions.TABLE);
	}

	PayOrderStateMachine(PayOrderStatePort port, LockRunner lockRunner, TxRunner txRunner,
			TransitionTable<PayOrderState, PayOrderEvent, PayOrder, PayOrderTransitionContext> table) {
		this.port = port;
		this.lockRunner = lockRunner;
		this.txRunner = txRunner;
		this.table = table;
	}

	public TransitionTable<PayOrderState, PayOrderEvent, PayOrder, PayOrderTransitionContext> table() {
		return table;
	}

	public static String lockKey(String payNo) {
		return "payorder:" + payNo;
	}

	public Transition<PayOrderState> fire(String payNo, PayOrderEvent event, PayOrderTransitionContext ctx) {
		return lockRunner.withLock(lockKey(payNo), LOCK_WAIT, () -> txRunner.inTx(() -> doFire(payNo, event, ctx)));
	}

	Transition<PayOrderState> doFire(String payNo, PayOrderEvent event, PayOrderTransitionContext ctx) {
		PayOrder p = port.loadForUpdate(payNo);
		if (p == null) {
			return reject(payNo, null, event, Transition.RejectKind.NOT_FOUND, "支付单不存在");
		}
		PayOrderState from = PayOrderState.of(p.getStatus());
		if (PAID_LIKE.contains(from) && CONFIRMS.contains(event)) {
			String incoming = ctx.channelTxnId();
			if (incoming == null || incoming.isBlank() || Objects.equals(incoming, p.getChannelTxnId())) {
				Metrics.counter("pay_order_transition_total", "event", event.name(), "outcome", "NOOP").increment();
				log.info("【状态机】支付单 {} 在 {} 收到重复的确认收款（流水号 {}），无操作", payNo, from, incoming);
				return Transition.noop(from);
			}
			return reject(payNo, from, event, Transition.RejectKind.GUARD,
					DUP_CHARGE + "：已付支付单收到不同渠道流水号的确认收款（原 " + p.getChannelTxnId() + "，新 " + incoming + "）");
		}
		Rule<PayOrderState, PayOrderEvent, PayOrder, PayOrderTransitionContext> rule = table.find(from, event);
		if (rule == null) {
			if (table.isIdempotentRepeat(from, event)) {
				Metrics.counter("pay_order_transition_total", "event", event.name(), "outcome", "NOOP").increment();
				log.info("【状态机】支付单 {} 在 {} 收到重复的 {}，无操作", payNo, from, event);
				return Transition.noop(from);
			}
			return reject(payNo, from, event, Transition.RejectKind.NO_RULE,
					"支付单当前状态（" + from + "）不允许 " + event);
		}
		Operator operator = ctx.operatorType() == null ? Operator.SYSTEM : ctx.operatorType();
		if (!rule.operators().contains(operator)) {
			return reject(payNo, from, event, Transition.RejectKind.GUARD,
					operator + " 不能触发 " + event + "（规则 " + rule.id() + "）");
		}
		String denied = rule.guard().check(p, ctx);
		if (denied != null) {
			return reject(payNo, from, event, Transition.RejectKind.GUARD, denied);
		}
		PayOrderState to = rule.target().resolve(p, ctx);
		if (to == null || to == PayOrderState.LEGACY_INVALID) {
			return reject(payNo, from, event, Transition.RejectKind.GUARD, "目标状态非法：" + to);
		}
		PayOrderPatch patch = patchFor(event, ctx);
		int n = port.compareAndSet(payNo, p.getStatus(), to, patch);
		if (n == 0) {
			throw new ConcurrentTransitionException("支付单 " + payNo + " 在 " + from + " --" + event + "--> " + to
					+ " 时被并发修改");
		}
		PayOrderStateLog row = new PayOrderStateLog();
		row.setId(port.nextId());
		row.setPayOrderId(p.getId());
		row.setPayNo(payNo);
		row.setFromStatus(from.name());
		row.setToStatus(to.name());
		row.setEvent(event.name());
		row.setRuleId(rule.id());
		row.setOperatorType(operator.name());
		row.setChannelTxnId(patch.channelTxnId() != null ? patch.channelTxnId() : p.getChannelTxnId());
		row.setCallbackLogId(ctx.callbackLogId());
		row.setReason(ctx.reason() == null ? null : truncate(ctx.reason(), 255));
		row.setTenantId(p.getTenantId() == null ? "-" : p.getTenantId());
		port.appendLog(row);
		port.publish(new OrderTransitionedEvent(row.getId(), EVENT_TYPE, 1, p.getTenantId(), OffsetDateTime.now(),
				p.getBizId(), p.getOrderNo(), payNo, from.name(), to.name(), event.name(), rule.id(),
				operator.name(), null, PayOrderTransitions.amountMinor(p),
				p.getCurrency() != null ? p.getCurrency() : p.getCurrCode(), ctx.callbackLogId()));
		Metrics.counter("pay_order_transition_total", "event", event.name(), "outcome", "OK").increment();
		log.info("【状态机】支付单 {} {} --{}--> {}（规则 {}，{}，流水 {}）", payNo, from, event, to, rule.id(), operator,
				row.getId());
		return Transition.ok(from, to, rule.id(), row.getId());
	}

	/** 伴随列由事件决定，调用方只给事实（上下文），不直接拼补丁——避免借道写别的列。 */
	static PayOrderPatch patchFor(PayOrderEvent event, PayOrderTransitionContext ctx) {
		PayOrderPatch.PayOrderPatchBuilder b = PayOrderPatch.builder();
		switch (event) {
			case PAY_CONFIRMED, LATE_PAY_CONFIRMED, DUPLICATE_PAY_CONFIRMED -> {
				b.channelTxnId(blankToNull(ctx.channelTxnId()));
				b.payTime(ctx.payTime() == null ? LocalDateTime.now() : ctx.payTime());
				if (event == PayOrderEvent.LATE_PAY_CONFIRMED) {
					b.latePaid(true);
				}
				if (event == PayOrderEvent.DUPLICATE_PAY_CONFIRMED) {
					b.duplicatePaid(true);
				}
			}
			case CHANNEL_REJECTED, CHANNEL_FAILED -> b.failReason(
					ctx.failReason() == null ? null : truncate(ctx.failReason(), 255));
			case ORDER_CLOSED -> b.channelClosed(Boolean.TRUE.equals(ctx.channelClosed())).closedTime(OffsetDateTime.now());
			default -> {
			}
		}
		return b.build();
	}

	private Transition<PayOrderState> reject(String payNo, PayOrderState from, PayOrderEvent event,
			Transition.RejectKind kind, String reason) {
		Metrics.counter("pay_order_transition_rejected_total", "from", String.valueOf(from), "event", event.name(),
				"kind", kind.name()).increment();
		log.warn("【状态机】支付单 {} 拒绝 {} --{}--> ：{}（{}）", payNo, from, event, reason, kind);
		return Transition.rejected(from, kind, reason);
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}
}
