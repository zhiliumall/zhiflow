package com.zhiflow.mall.common.trade.payexception;

import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 一条待入池的差错。
 *
 * <p>{@code dedupeScope} 决定「同一笔钱」的判据，与 {@code type} 拼成 {@code pay_exception.dedupe_key}。
 * 同一笔钱可能被回调、补单任务、对账先后发现——判据必须是这几条路径都拿得到的东西：
 * <ul>
 *   <li>老渠道（渠道单号 = 订单号，一单一个渠道单）：{@link #scopeOfOrder}；</li>
 *   <li>新框架（一单多张支付单）：{@link #scopeOfPayOrder}；</li>
 *   <li>渠道侧重复扣款（同一渠道单、不同流水号）：{@link #scopeOfTxn}。</li>
 * </ul>
 */
@Builder
public record PayExceptionDraft(
		PayExceptionType type,
		String dedupeScope,
		String tenantId,
		String orderId,
		String orderNo,
		String payOrderId,
		String channel,
		String channelTxnId,
		Long amountMinor,
		String currency,
		String callbackLogId,
		String detail) {

	public PayExceptionDraft {
		Objects.requireNonNull(type, "type");
		if (dedupeScope == null || dedupeScope.isBlank()) {
			throw new IllegalArgumentException("dedupeScope 不能为空：没有判据就无法保证同一笔钱只入池一次");
		}
	}

	/** 补上支付单 id（调用方只有支付单号时查出来再补；阶段 2 的自动退款按它定位要退的那张支付单）。 */
	public PayExceptionDraft withPayOrderId(String id) {
		return new PayExceptionDraft(type, dedupeScope, tenantId, orderId, orderNo, id, channel, channelTxnId,
				amountMinor, currency, callbackLogId, detail);
	}

	/** {@code pay_exception.dedupe_key}，长度受列宽 160 约束。 */
	public String dedupeKey() {
		String key = type.name() + ":" + dedupeScope;
		return key.length() <= 160 ? key : key.substring(0, 160);
	}

	public static String scopeOfOrder(String orderId, String channel) {
		return "order:" + orderId + ":" + (channel == null ? "-" : channel);
	}

	public static String scopeOfPayOrder(String payOrderId) {
		return "po:" + payOrderId;
	}

	public static String scopeOfTxn(String channel, String channelTxnId) {
		return "txn:" + (channel == null ? "-" : channel) + ":" + channelTxnId;
	}

	/** 元（numeric(10,2)）→ 最小单位。null 进 null 出。 */
	public static Long toMinor(BigDecimal yuan) {
		return yuan == null ? null : yuan.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
	}
}
