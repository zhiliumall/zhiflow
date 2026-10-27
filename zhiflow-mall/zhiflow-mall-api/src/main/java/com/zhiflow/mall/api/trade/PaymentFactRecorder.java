package com.zhiflow.mall.api.trade;

import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.trade.payexception.OrderPaymentFacts;
import com.zhiflow.mall.common.trade.payexception.PayExceptionDraft;
import com.zhiflow.mall.common.trade.payexception.PayExceptionRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 入账路径上「钱到了但正常路径进不了账」的落点（支付与订单状态机 v1.2 阶段 0 · 0.2 / 0.3）。
 *
 * <p>判据在 {@link OrderPaymentFacts}（mall-common，与 mall-admin 共用），这里只负责入池。
 * 返回值由调用方决定失败时怎么办：MQ 入账抛异常让消息重投，回调回 503 让渠道重推。
 */
@Component
@RequiredArgsConstructor
public class PaymentFactRecorder {

	private final PayExceptionRecorder payExceptionRecorder;

	private final com.zhiflow.mall.api.mapper.PayExceptionMapper payExceptionMapper;

	/** 阶段 2：入池时补上支付单 id（自动退款按它定位要退的那张支付单）。 */
	private final com.zhiflow.mall.api.mapper.PayOrderMapper payOrderMapper;

	/**
	 * 这张（已取消）订单的迟到到账是否已入池。补单任务据此跳过已处理的单，不再反复对外查单。
	 * 判据与 {@link #latePaid} 拼出的 dedupe_key 一致（老渠道按「订单 + 渠道」）。
	 */
	public boolean latePaidRecorded(OrderInfo order) {
		String key = OrderPaymentFacts.latePaid(order, order, "").dedupeKey();
		return payExceptionMapper.exists(com.baomidou.mybatisplus.core.toolkit.Wrappers
				.<com.zhiflow.mall.common.entity.PayException>lambdaQuery()
				.eq(com.zhiflow.mall.common.entity.PayException::getDedupeKey, key));
	}

	/** 已取消（未付）订单收到确认到账 → LATE_PAID。见 {@link OrderPaymentFacts#latePaid}。 */
	public PayExceptionRecorder.Result latePaid(OrderInfo dbOrder, OrderInfo incoming, String source) {
		return payExceptionRecorder.record(withPayOrder(OrderPaymentFacts.latePaid(dbOrder, incoming, source), incoming));
	}

	/** 这笔钱来自某张支付单（新框架入账消息带支付单号）→ 草稿补上支付单 id。查不到不影响入池。 */
	private PayExceptionDraft withPayOrder(PayExceptionDraft draft, OrderInfo incoming) {
		if (draft == null || draft.payOrderId() != null || incoming.getChannelPayNo() == null
				|| incoming.getChannelPayNo().isBlank()) {
			return draft;
		}
		com.zhiflow.mall.common.entity.PayOrder p = payOrderMapper.selectOne(
				com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.zhiflow.mall.common.entity.PayOrder>lambdaQuery()
						.eq(com.zhiflow.mall.common.entity.PayOrder::getPayNo, incoming.getChannelPayNo())
						.last("LIMIT 1"));
		return p == null ? draft : draft.withPayOrderId(p.getId());
	}

	/**
	 * 已付订单又收到一笔确认到账 → DUP_PAY / DUP_CHARGE。见 {@link OrderPaymentFacts#secondPaymentIfAny}。
	 *
	 * @return 入池结果；不是第二笔钱时返回 {@code null}
	 */
	public PayExceptionRecorder.Result secondPaymentIfAny(OrderInfo dbOrder, OrderInfo incoming) {
		PayExceptionDraft draft = withPayOrder(OrderPaymentFacts.secondPaymentIfAny(dbOrder, incoming), incoming);
		return draft == null ? null : payExceptionRecorder.record(draft);
	}

	/** 渠道回报金额 / 币种与订单应付不一致 → AMOUNT_MISMATCH。 */
	public PayExceptionRecorder.Result amountMismatch(OrderInfo order, String channel, String channelTxnId,
			BigDecimal channelAmount, String channelCurrency, String detail) {
		return payExceptionRecorder.record(OrderPaymentFacts.amountMismatch(order, channel, channelTxnId,
				channelAmount, channelCurrency, detail));
	}
}
