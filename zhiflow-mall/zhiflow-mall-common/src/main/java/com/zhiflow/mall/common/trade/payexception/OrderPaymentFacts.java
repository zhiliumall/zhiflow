package com.zhiflow.mall.common.trade.payexception;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.mall.common.entity.OrderInfo;

import java.math.BigDecimal;

/**
 * 从订单侧上下文构造差错池草稿（支付与订单状态机 v1.2 阶段 0 · 0.2 / 0.3）。
 *
 * <p>判据与 dedupe 口径集中在这里：mall-api（回调、MQ 入账、补单任务）与 mall-admin（收银台入账）
 * 发现同一笔钱时拼出同一个 {@code dedupe_key}，只入池一次。纯函数，不碰数据库。
 */
public final class OrderPaymentFacts {

	private OrderPaymentFacts() {
	}

	/** 老渠道「渠道单号 = 订单号」，默认币种 CNY。 */
	static final String DEFAULT_CURRENCY = "CNY";

	/**
	 * 已取消（未付）订单收到确认到账 → LATE_PAID（v1.2 P7 / 2.7）。
	 *
	 * @param dbOrder  锁内重读的订单（已取消）
	 * @param incoming 携带渠道事实的入账请求（支付方式、流水号、实收金额），可与 dbOrder 同一对象
	 * @param source   发现路径，写进 detail（CALLBACK / RECONCILE_JOB / CANCEL_GATE …）
	 */
	public static PayExceptionDraft latePaid(OrderInfo dbOrder, OrderInfo incoming, String source) {
		String channel = firstNonBlank(incoming.getPaymentType(), dbOrder.getPaymentType());
		return PayExceptionDraft.builder()
				.type(PayExceptionType.LATE_PAID)
				.dedupeScope(StrUtil.isNotBlank(incoming.getChannelPayNo())
						? PayExceptionDraft.scopeOfPayOrder(incoming.getChannelPayNo())
						: PayExceptionDraft.scopeOfOrder(dbOrder.getId(), channel))
				.tenantId(dbOrder.getTenantId())
				.orderId(dbOrder.getId())
				.orderNo(dbOrder.getOrderNo())
				.channel(channel)
				.channelTxnId(incoming.getTransactionId())
				.amountMinor(PayExceptionDraft.toMinor(paidAmount(dbOrder, incoming)))
				.currency(paidCurrency(incoming))
				.callbackLogId(incoming.getChannelCallbackLogId())
				.detail("订单已取消后才确认到账；订单不复活、库存权益不重扣。发现路径=" + source
						+ (incoming.getChannelPaidAmount() == null ? "；金额取订单应付（渠道未回报实收）" : "")
						+ "。v1.2：阶段 2 起 ≤ 上限自动原路退，之前人工退款或恢复订单")
				.build();
	}

	/**
	 * 已付订单又收到一笔确认到账：判断是不是「另一笔钱」。
	 *
	 * <ul>
	 *   <li>支付方式不同 → DUP_PAY（v1.2 P9：同订单第二张支付单也付了，后付的应全额退）；</li>
	 *   <li>支付方式相同、流水号不同 → DUP_CHARGE（同一渠道单被扣两次，需向渠道核实，不自动退）；</li>
	 *   <li>其余（同方式同流水、或信息不足）→ 重复投递，不入池。</li>
	 * </ul>
	 *
	 * <p>⚠️ 不同渠道查单接口返回的流水号字段口径可能不一致；若取错了字段，补单路径可能把同一笔钱误判成
	 * DUP_CHARGE。所以这里只入池待人工核实，<b>绝不自动退款</b>。
	 *
	 * @return 草稿；不是第二笔钱时返回 {@code null}
	 */
	public static PayExceptionDraft secondPaymentIfAny(OrderInfo dbOrder, OrderInfo incoming) {
		String inType = incoming.getPaymentType();
		String inTxn = incoming.getTransactionId();
		String dbType = dbOrder.getPaymentType();
		String dbTxn = dbOrder.getTransactionId();
		boolean typeDiffers = StrUtil.isNotBlank(inType) && StrUtil.isNotBlank(dbType) && !inType.equals(dbType);
		boolean txnDiffers = StrUtil.isNotBlank(inTxn) && StrUtil.isNotBlank(dbTxn) && !inTxn.equals(dbTxn);
		if (!typeDiffers && !txnDiffers) {
			return null;
		}
		PayExceptionType type = typeDiffers ? PayExceptionType.DUP_PAY : PayExceptionType.DUP_CHARGE;
		String scope = typeDiffers
				? PayExceptionDraft.scopeOfOrder(dbOrder.getId(), inType) + ":" + StrUtil.blankToDefault(inTxn, "-")
				: PayExceptionDraft.scopeOfTxn(inType, inTxn);
		return PayExceptionDraft.builder()
				.type(type)
				.dedupeScope(scope)
				.tenantId(dbOrder.getTenantId())
				.orderId(dbOrder.getId())
				.orderNo(dbOrder.getOrderNo())
				.channel(inType)
				.channelTxnId(inTxn)
				.amountMinor(PayExceptionDraft.toMinor(paidAmount(dbOrder, incoming)))
				.currency(paidCurrency(incoming))
				.callbackLogId(incoming.getChannelCallbackLogId())
				.detail("订单已付（方式=" + dbType + " 流水=" + dbTxn + "），又收到方式=" + inType + " 流水=" + inTxn
						+ " 的确认到账；订单不动。" + (typeDiffers ? "按 P9 应全额退回后付的一笔" : "需向渠道核实是否重复扣款"))
				.build();
	}

	/**
	 * 渠道回报金额 / 币种与订单应付不一致 → AMOUNT_MISMATCH（⑧）。不入账、回 200。
	 */
	public static PayExceptionDraft amountMismatch(OrderInfo order, String channel, String channelTxnId,
			BigDecimal channelAmount, String channelCurrency, String detail) {
		return PayExceptionDraft.builder()
				.type(PayExceptionType.AMOUNT_MISMATCH)
				.dedupeScope(StrUtil.isNotBlank(channelTxnId) ? PayExceptionDraft.scopeOfTxn(channel, channelTxnId)
						: PayExceptionDraft.scopeOfOrder(order.getId(), channel))
				.tenantId(order.getTenantId())
				.orderId(order.getId())
				.orderNo(order.getOrderNo())
				.channel(channel)
				.channelTxnId(channelTxnId)
				.amountMinor(PayExceptionDraft.toMinor(channelAmount))
				.currency(StrUtil.blankToDefault(channelCurrency, DEFAULT_CURRENCY))
				.detail("应付=" + order.getPaymentPrice() + " CNY；" + detail)
				.build();
	}

	private static BigDecimal paidAmount(OrderInfo dbOrder, OrderInfo incoming) {
		return incoming.getChannelPaidAmount() != null ? incoming.getChannelPaidAmount() : dbOrder.getPaymentPrice();
	}

	private static String paidCurrency(OrderInfo incoming) {
		return StrUtil.blankToDefault(incoming.getChannelPaidCurrency(), DEFAULT_CURRENCY);
	}

	private static String firstNonBlank(String a, String b) {
		return StrUtil.isNotBlank(a) ? a : b;
	}
}
