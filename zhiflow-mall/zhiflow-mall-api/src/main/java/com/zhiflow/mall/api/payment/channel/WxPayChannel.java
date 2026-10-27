package com.zhiflow.mall.api.payment.channel;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
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
import com.zhiflow.pay.common.dto.inside.WxOrderNotifyDTO;
import com.zhiflow.pay.common.dto.inside.WxOrderQueryDTO;
import com.zhiflow.pay.common.dto.inside.WxOrderQueryReq;
import com.zhiflow.pay.common.dto.inside.WxUnifiedOrderReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信支付（国内，V2 接口，普通商户模式）。
 *
 * <p>公众号内（{@code appType=H5-WX}）走 JSAPI，返回调起参数；普通浏览器走 MWEB（H5 支付），返回跳转地址。
 * 商户订单号用支付单号 {@code pay_no}；回调地址 {@code /mallapi/paynotify/1/{tenantId}}，
 * 验签与 return_code / result_code 校验由 pay-admin 的 WxJava {@code parseOrderNotifyResult} 完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxPayChannel implements PaymentChannel {

	public static final String CODE = PaymentTypeEnum.WECHAT.getValue();

	/** 微信公众号内 H5 的 appType，与前端约定一致。 */
	static final String APP_TYPE_WX_H5 = "H5-WX";

	static final String ACK_SUCCESS = "<xml><return_code><![CDATA[SUCCESS]]></return_code>"
			+ "<return_msg><![CDATA[OK]]></return_msg></xml>";

	private static final DateTimeFormatter TIME_END = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

	private final ZhiflowPayApi zhiflowPayApi;

	private final MallConfigProperties mallConfigProperties;

	@Override
	public String code() {
		return CODE;
	}

	@Override
	public PaymentIntent createPayment(PaymentContext ctx, PayOrder payOrder) {
		boolean jsapi = APP_TYPE_WX_H5.equals(ctx.getAppType());
		if (jsapi && StrUtil.isBlank(ctx.getPayOpenId())) {
			throw new IllegalStateException("公众号内支付缺少 openid");
		}
		WxUnifiedOrderReq req = new WxUnifiedOrderReq();
		req.setBody(StrUtil.maxLength(StrUtil.blankToDefault(ctx.getSubject(), payOrder.getOrderNo()), 120));
		req.setOutTradeNo(payOrder.getPayNo());
		req.setTotalFee(toFen(payOrder.getAmount()));
		req.setTradeType(jsapi ? "JSAPI" : "MWEB");
		req.setNotifyUrl(PayNotifyUrls.of(mallConfigProperties, CODE, ctx.getTenantId()));
		req.setSpbillCreateIp(ctx.getClientIp());
		if (jsapi) {
			// 普通商户模式下 pay-admin 把 subAppId / subOpenid 还原成 appid / openid
			req.setSubAppId(ctx.getAppId());
			req.setSubOpenid(ctx.getPayOpenId());
		}
		R<String> r = zhiflowPayApi.unifiedOrderWx(req, SecurityConstants.FROM_IN);
		if (r == null || !r.isOk() || StrUtil.isBlank(r.getData())) {
			throw new IllegalStateException("微信下单失败：" + (r == null ? "无响应" : r.getMsg()));
		}
		JSONObject result = JSONUtil.parseObj(r.getData());
		PaymentIntent.PaymentIntentBuilder intent = PaymentIntent.builder().payNo(payOrder.getPayNo()).channelCode(CODE);
		if (jsapi) {
			// WxPayMpOrderResult 的 package 字段在 Java 里叫 packageValue，这里还原成 JSSDK 要的名字
			Map<String, Object> params = new LinkedHashMap<>();
			params.put("appId", result.getStr("appId"));
			params.put("timeStamp", result.getStr("timeStamp"));
			params.put("nonceStr", result.getStr("nonceStr"));
			params.put("package", result.getStr("packageValue"));
			params.put("signType", result.getStr("signType"));
			params.put("paySign", result.getStr("paySign"));
			return intent.type(PaymentIntent.Type.SDK).payParams(params).build();
		}
		String mwebUrl = result.getStr("mwebUrl");
		if (StrUtil.isBlank(mwebUrl)) {
			throw new IllegalStateException("微信 H5 下单响应缺少 mweb_url");
		}
		if (StrUtil.isNotBlank(ctx.getReturnUrl())) {
			mwebUrl = mwebUrl + "&redirect_url=" + URLEncoder.encode(ctx.getReturnUrl(), StandardCharsets.UTF_8);
		}
		return intent.type(PaymentIntent.Type.REDIRECT).redirectUrl(mwebUrl).build();
	}

	/**
	 * 查单。SUCCESS → 已付；NOTPAY / USERPAYING → 未付；CLOSED / REVOKED / PAYERROR → 失败；
	 * 查不通（含 ORDERNOTEXIST，pay-admin 统一回 R.failed）→ UNKNOWN。
	 */
	@Override
	public PaymentQueryResult query(PayOrder payOrder) {
		WxOrderQueryReq req = new WxOrderQueryReq();
		req.setOutTradeNo(payOrder.getPayNo());
		R<WxOrderQueryDTO> r;
		try {
			r = zhiflowPayApi.queryOrderWx(req, SecurityConstants.FROM_IN);
		}
		catch (Exception e) {
			log.warn("微信查单异常 payNo={}", payOrder.getPayNo(), e);
			return PaymentQueryResult.unknown("查单异常：" + e.getMessage());
		}
		if (r == null || !r.isOk() || r.getData() == null) {
			return PaymentQueryResult.unknown("查单失败：" + (r == null ? "无响应" : r.getMsg()));
		}
		WxOrderQueryDTO q = r.getData();
		String state = q.getTradeState();
		if ("SUCCESS".equals(state)) {
			return PaymentQueryResult.builder().state(PaymentQueryResult.State.PAID)
					.channelTxnId(q.getTransactionId())
					.paidAmount(fromFen(q.getTotalFee()))
					.currCode("CNY")
					.payTime(parseTimeEnd(q.getTimeEnd()))
					.rawMessage(state)
					.build();
		}
		if ("CLOSED".equals(state) || "REVOKED".equals(state) || "PAYERROR".equals(state)) {
			return PaymentQueryResult.builder().state(PaymentQueryResult.State.FAILED).rawMessage(state).build();
		}
		return PaymentQueryResult.builder().state(PaymentQueryResult.State.NOT_PAID).rawMessage(state).build();
	}

	@Override
	public CallbackResult parseCallback(RawCallback raw) {
		if (StrUtil.isBlank(raw.getBody())) {
			return CallbackResult.invalidSign(null);
		}
		R<WxOrderNotifyDTO> r;
		try {
			r = zhiflowPayApi.notifyOrderWx(raw.getBody(), SecurityConstants.FROM_IN);
		}
		catch (Exception e) {
			log.error("微信回调验签异常 tenantId={}", raw.getTenantId(), e);
			return CallbackResult.invalidSign(null);
		}
		if (r == null || !r.isOk() || r.getData() == null) {
			// 验签失败或 return_code / result_code 非 SUCCESS：WxJava 一律抛异常，pay-admin 回 R.failed
			log.error("微信回调验签未通过 tenantId={} msg={}", raw.getTenantId(), r == null ? null : r.getMsg());
			return CallbackResult.invalidSign(null);
		}
		WxOrderNotifyDTO n = r.getData();
		return CallbackResult.builder()
				.signValid(true)
				.payNo(n.getOutTradeNo())
				.channelTxnId(n.getTransactionId())
				.idempotentKey(n.getTransactionId())
				.event(CallbackResult.Event.PAID)
				.amount(fromFen(n.getTotalFee()))
				.currCode("CNY")
				.channelStatus("SUCCESS")
				.ackBody(ACK_SUCCESS)
				.ackContentType("text/xml")
				.build();
	}

	static int toFen(BigDecimal yuan) {
		return yuan.movePointRight(2).intValueExact();
	}

	static BigDecimal fromFen(Integer fen) {
		return fen == null ? null : BigDecimal.valueOf(fen, 2);
	}

	static LocalDateTime parseTimeEnd(String timeEnd) {
		try {
			return StrUtil.isBlank(timeEnd) ? null : LocalDateTime.parse(timeEnd, TIME_END);
		}
		catch (Exception e) {
			return null;
		}
	}
}
