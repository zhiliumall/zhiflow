package com.zhiflow.mall.api.trade;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.mall.api.mapper.PayOrderMapper;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.enums.PayBizTypeEnum;
import com.zhiflow.mall.common.enums.PaymentTypeEnum;
import com.zhiflow.mall.common.trade.statemachine.order.OrderActionResolver;
import com.zhiflow.mall.common.trade.statemachine.order.OrderState;
import com.zhiflow.mall.common.trade.statemachine.payorder.PayOrderState;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 支付结果查询：前端「永远不自己判成功或失败」，一律以本接口为准。
 *
 * <p>state 以支付单为准：UNPAID（没发起）/ PAYING / PAID / FAILED（仅渠道明确失败）/ CLOSED（订单已取消）/
 * UNKNOWN（查不通——前端按「确认中」处理，<b>不能当失败</b>）。支付单 PAID 而订单还是待付款（入账事件未消费）也回 PAID。
 */
@Service
public class PayStatusService {

	/** 订单已付后的状态（都算「支付成功」页）。 */
	private static final Set<OrderState> PAID_STATES = Set.of(OrderState.PENDING_SHIP, OrderState.PENDING_RECEIVE,
			OrderState.COMPLETED);

	/** 前 10 秒每秒一次、之后每 3 秒一次，总时长 60 秒（服务端下发，前端不写死）。 */
	static final long DENSE_MS = 10_000;

	static final long TOTAL_MS = 60_000;

	private final PayOrderMapper payOrderMapper;

	private final PayStatusProbe probe;

	public PayStatusService(PayOrderMapper payOrderMapper, PayStatusProbe probe) {
		this.payOrderMapper = payOrderMapper;
		this.probe = probe;
	}

	/** 接口返回体。 */
	public record PayStatus(String state, String orderId, String orderNo, String orderStatus, String orderStatusDesc,
			String paymentType, String paymentTypeName, BigDecimal amount, String payNo, BigDecimal payAmount,
			String payCurrency, LocalDateTime paidAt, LocalDateTime createdAt, Long payRemainSeconds, String failReason,
			String retry, long nextCheckAfterMs, boolean probeSubmitted, List<OrderActionResolver.ActionView> actions) {
	}

	/**
	 * @param probe     是否顺带向渠道探查（异步，不等结果）
	 * @param elapsedMs 前端从第一次查询起已过去的毫秒数（决定 nextCheckAfterMs；不传按第一次）
	 */
	public PayStatus query(OrderInfo order, boolean probe, Long elapsedMs, LocalDateTime now) {
		List<PayOrder> payOrders = payOrderMapper.selectList(Wrappers.<PayOrder>lambdaQuery()
				.eq(PayOrder::getBizType, PayBizTypeEnum.ORDER.getValue())
				.eq(PayOrder::getBizId, order.getId()));
		PayOrder latest = payOrders.stream().max(Comparator.comparing(PayOrder::getCreateTime,
				Comparator.nullsFirst(Comparator.naturalOrder()))).orElse(null);
		PayOrder paid = payOrders.stream().filter(p -> PayOrderState.of(p.getStatus()) == PayOrderState.PAID)
				.findFirst().orElse(null);
		OrderState os = OrderState.of(order.getStatus(), order.getIsPay());
		boolean submitted = false;
		if (probe && os == OrderState.PENDING_PAY && paid == null && latest != null && isOpen(latest)) {
			submitted = this.probe.submit(order, latest);
		}
		String lastProbe = os == OrderState.PENDING_PAY && paid == null
				? this.probe.lastResult(order, latest != null ? latest : null) : null;
		String state = deriveState(os, latest, paid, lastProbe);
		Long remain = payRemainSeconds(order, os, now);
		PayOrder shown = paid != null ? paid : latest;
		return new PayStatus(state, order.getId(), order.getOrderNo(), order.getStatus(), order.getStatusDesc(),
				shown != null ? shown.getChannelCode() : order.getPaymentType(),
				PaymentTypeEnum.getDesc(shown != null ? shown.getChannelCode() : order.getPaymentType()),
				order.getPaymentPrice(), shown == null ? null : shown.getPayNo(), shown == null ? null : shown.getAmount(),
				shown == null ? null : shown.getCurrency(),
				paid != null && paid.getPayTime() != null ? paid.getPayTime() : order.getPaymentTime(),
				order.getCreateTime(), remain,
				"FAILED".equals(state) && latest != null ? latest.getFailReason() : null,
				retry(state, latest, now), nextCheckAfterMs(state, elapsedMs), submitted,
				OrderActionResolver.resolve(order, OrderActionResolver.Side.USER,
						new OrderActionResolver.Facts(remain), now));
	}

	/** 纯函数，便于穷举测试。 */
	static String deriveState(OrderState os, PayOrder latest, PayOrder paid, String lastProbe) {
		if (PAID_STATES.contains(os)) {
			return "PAID";
		}
		if (os == OrderState.CANCELLED) {
			return "CLOSED";
		}
		if (os != OrderState.PENDING_PAY) {
			return "UNKNOWN";
		}
		if (paid != null) {
			return "PAID";
		}
		if (latest == null) {
			return "UNPAID";
		}
		return switch (PayOrderState.of(latest.getStatus())) {
			case CREATED, PAYING -> "UNKNOWN".equals(lastProbe) ? "UNKNOWN" : "PAYING";
			case FAILED -> "FAILED";
			default -> "UNPAID";
		};
	}

	static String retry(String state, PayOrder latest, LocalDateTime now) {
		if (!"UNPAID".equals(state) && !"PAYING".equals(state) && !"FAILED".equals(state) && !"UNKNOWN".equals(state)) {
			return "NONE";
		}
		if (latest != null && isOpen(latest)) {
			return "REUSE_LINK";
		}
		return "NEW_PAYMENT";
	}

	static long nextCheckAfterMs(String state, Long elapsedMs) {
		if (!"PAYING".equals(state) && !"UNKNOWN".equals(state) && !"UNPAID".equals(state)) {
			return 0;
		}
		long e = elapsedMs == null ? 0 : elapsedMs;
		if (e >= TOTAL_MS) {
			return 0;
		}
		return e < DENSE_MS ? 1000 : 3000;
	}

	public static Long payRemainSeconds(OrderInfo order, OrderState os, LocalDateTime now) {
		if (os != OrderState.PENDING_PAY || order.getCreateTime() == null) {
			return null;
		}
		long left = Duration.between(now, order.getCreateTime().plusMinutes(MallConstants.ORDER_TIME_OUT_0)).getSeconds();
		return Math.max(0, left);
	}

	private static boolean isOpen(PayOrder p) {
		PayOrderState s = PayOrderState.of(p.getStatus());
		return (s == PayOrderState.CREATED || s == PayOrderState.PAYING)
				&& (p.getExpireTime() == null || p.getExpireTime().isAfter(LocalDateTime.now()));
	}
}
