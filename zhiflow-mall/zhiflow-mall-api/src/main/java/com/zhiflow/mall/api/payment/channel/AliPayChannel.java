package com.zhiflow.mall.api.payment.channel;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import cn.hutool.json.JSONUtil;
import com.zhiflow.api.pay.ZhiflowPayApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.common.config.MallConfigProperties;
import com.zhiflow.mall.api.payment.PaymentChannel;
import com.zhiflow.mall.api.payment.dto.CallbackResult;
import com.zhiflow.mall.api.payment.dto.PaymentContext;
import com.zhiflow.mall.api.payment.dto.PaymentIntent;
import com.zhiflow.mall.api.payment.dto.PaymentQueryResult;
import com.zhiflow.mall.api.payment.dto.RawCallback;
import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.mall.common.enums.PaymentTypeEnum;
import com.zhiflow.pay.common.dto.inside.AliOrderNotifyDTO;
import com.zhiflow.pay.common.dto.inside.AliOrderQueryDTO;
import com.zhiflow.pay.common.dto.inside.AliOrderQueryReq;
import com.zhiflow.pay.common.dto.inside.AliUnifiedOrderReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 支付宝（国内）手机网站支付。
 *
 * <p>商户订单号用支付单号 {@code pay_no}；回调地址 {@code /mallapi/paynotify/2/{tenantId}}，
 * 由 pay-admin 用 easysdk {@code verifyNotify} 验签并核对 app_id / seller_id（方案 S-2）。
 * 只有 {@code TRADE_SUCCESS} / {@code TRADE_FINISHED} 算已付。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AliPayChannel implements PaymentChannel {

	public static final String CODE = PaymentTypeEnum.ALIPAY.getValue();

	static final String ACK_SUCCESS = "success";

	private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*\\saction=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

	private static final Pattern HIDDEN_INPUT = Pattern.compile(
			"<input[^>]*\\sname=\"([^\"]+)\"[^>]*\\svalue=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

	private static final DateTimeFormatter ALI_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final ZhiflowPayApi zhiflowPayApi;

	private final MallConfigProperties mallConfigProperties;

	@Override
	public String code() {
		return CODE;
	}

	@Override
	public PaymentIntent createPayment(PaymentContext ctx, PayOrder payOrder) {
		AliUnifiedOrderReq req = new AliUnifiedOrderReq();
		req.setTradeType("MWEB");
		req.setOutTradeNo(payOrder.getPayNo());
		req.setTotalAmount(payOrder.getAmount());
		req.setNotifyUrl(PayNotifyUrls.of(mallConfigProperties, CODE, ctx.getTenantId()));
		req.setReturnUrl(StrUtil.blankToDefault(ctx.getReturnUrl(), ""));
		req.setQuitUrl(StrUtil.blankToDefault(ctx.getQuitUrl(), StrUtil.blankToDefault(ctx.getReturnUrl(), "")));
		req.setSubject(StrUtil.maxLength(StrUtil.blankToDefault(ctx.getSubject(), payOrder.getOrderNo()), 120));
		R<String> r = zhiflowPayApi.unifiedOrderAli(req, SecurityConstants.FROM_IN);
		if (r == null || !r.isOk() || StrUtil.isBlank(r.getData())) {
			throw new IllegalStateException("支付宝下单失败：" + (r == null ? "无响应" : r.getMsg()));
		}
		String form = JSONUtil.parseObj(r.getData()).getStr("body");
		return PaymentIntent.builder()
				.payNo(payOrder.getPayNo())
				.channelCode(CODE)
				.type(PaymentIntent.Type.REDIRECT)
				.redirectUrl(formToUrl(form))
				.build();
	}

	/**
	 * easysdk 的手机网站支付返回一段自动提交的 HTML 表单。网关同样接受 GET，
	 * 把表单的 action 与隐藏字段拼成一个地址，前端就只需要普通跳转。
	 */
	static String formToUrl(String form) {
		if (StrUtil.isBlank(form)) {
			throw new IllegalStateException("支付宝下单响应缺少表单");
		}
		Matcher action = FORM_ACTION.matcher(form);
		if (!action.find()) {
			throw new IllegalStateException("支付宝下单响应表单缺少 action");
		}
		StringBuilder url = new StringBuilder(HtmlUtil.unescape(action.group(1)));
		Matcher input = HIDDEN_INPUT.matcher(form);
		while (input.find()) {
			url.append(url.indexOf("?") < 0 ? '?' : '&')
					.append(URLEncoder.encode(HtmlUtil.unescape(input.group(1)), StandardCharsets.UTF_8))
					.append('=')
					.append(URLEncoder.encode(HtmlUtil.unescape(input.group(2)), StandardCharsets.UTF_8));
		}
		return url.toString();
	}

	/**
	 * 查单。TRADE_SUCCESS / TRADE_FINISHED → 已付；WAIT_BUYER_PAY → 未付；TRADE_CLOSED → 失败；
	 * 交易不存在（买家还没打开收银台）→ 未付；其余查不通 → UNKNOWN。
	 */
	@Override
	public PaymentQueryResult query(PayOrder payOrder) {
		AliOrderQueryReq req = new AliOrderQueryReq();
		req.setOutTradeNo(payOrder.getPayNo());
		R<AliOrderQueryDTO> r;
		try {
			r = zhiflowPayApi.queryOrderAli(req, SecurityConstants.FROM_IN);
		}
		catch (Exception e) {
			log.warn("支付宝查单异常 payNo={}", payOrder.getPayNo(), e);
			return PaymentQueryResult.unknown("查单异常：" + e.getMessage());
		}
		AliOrderQueryDTO q = r == null ? null : r.getData();
		if (q != null && "ACQ.TRADE_NOT_EXIST".equals(q.getSubCode())) {
			return PaymentQueryResult.builder().state(PaymentQueryResult.State.NOT_PAID).rawMessage(q.getSubCode()).build();
		}
		if (r == null || !r.isOk() || q == null) {
			return PaymentQueryResult.unknown("查单失败：" + (r == null ? "无响应" : r.getMsg()));
		}
		String status = q.getTradeStatus();
		if (isPaid(status)) {
			return PaymentQueryResult.builder().state(PaymentQueryResult.State.PAID)
					.channelTxnId(q.getTradeNo())
					.paidAmount(q.getTotalAmount())
					.currCode("CNY")
					.payTime(parseTime(q.getSendPayDate()))
					.rawMessage(status)
					.build();
		}
		if ("TRADE_CLOSED".equals(status)) {
			return PaymentQueryResult.builder().state(PaymentQueryResult.State.FAILED).rawMessage(status).build();
		}
		return PaymentQueryResult.builder().state(PaymentQueryResult.State.NOT_PAID).rawMessage(status).build();
	}

	@Override
	public CallbackResult parseCallback(RawCallback raw) {
		if (raw.getParams() == null || raw.getParams().isEmpty()) {
			return CallbackResult.invalidSign(null);
		}
		R<AliOrderNotifyDTO> r;
		try {
			r = zhiflowPayApi.notifyOrderAli(raw.getParams(), SecurityConstants.FROM_IN);
		}
		catch (Exception e) {
			log.error("支付宝回调验签异常 tenantId={}", raw.getTenantId(), e);
			return CallbackResult.invalidSign(null);
		}
		if (r == null || !r.isOk() || r.getData() == null) {
			log.error("支付宝回调验签未通过 tenantId={} msg={}", raw.getTenantId(), r == null ? null : r.getMsg());
			return CallbackResult.invalidSign(null);
		}
		AliOrderNotifyDTO n = r.getData();
		String status = n.getTradeStatus();
		CallbackResult.Event event = isPaid(status) ? CallbackResult.Event.PAID
				: "TRADE_CLOSED".equals(status) ? CallbackResult.Event.FAILED : CallbackResult.Event.UNKNOWN;
		return CallbackResult.builder()
				.signValid(true)
				.payNo(n.getOutTradeNo())
				.channelTxnId(n.getTradeNo())
				// 同一笔交易 TRADE_SUCCESS 之后还会来一条 TRADE_FINISHED，按「交易号 + 状态」各占一个幂等位
				.idempotentKey(n.getTradeNo() + ":" + status)
				.event(event)
				.amount(n.getTotalAmount())
				.currCode("CNY")
				.channelStatus(status)
				.ackBody(ACK_SUCCESS)
				.ackContentType("text/plain")
				.build();
	}

	static boolean isPaid(String tradeStatus) {
		return "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
	}

	static LocalDateTime parseTime(String value) {
		try {
			return StrUtil.isBlank(value) ? null : LocalDateTime.parse(value, ALI_TIME);
		}
		catch (Exception e) {
			return null;
		}
	}
}
