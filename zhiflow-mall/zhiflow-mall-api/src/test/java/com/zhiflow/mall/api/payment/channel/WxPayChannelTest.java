package com.zhiflow.mall.api.payment.channel;

import com.zhiflow.api.pay.ZhiflowPayApi;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.api.payment.dto.CallbackResult;
import com.zhiflow.mall.api.payment.dto.PaymentContext;
import com.zhiflow.mall.api.payment.dto.PaymentIntent;
import com.zhiflow.mall.api.payment.dto.PaymentQueryResult;
import com.zhiflow.mall.api.payment.dto.RawCallback;
import com.zhiflow.mall.common.config.MallConfigProperties;
import com.zhiflow.mall.common.entity.PayOrder;
import com.zhiflow.pay.common.dto.inside.WxOrderNotifyDTO;
import com.zhiflow.pay.common.dto.inside.WxOrderQueryDTO;
import com.zhiflow.pay.common.dto.inside.WxUnifiedOrderReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 国内微信支付渠道：下单形态（JSAPI / MWEB）、查单状态映射、回调解析。
 *
 * <p>只 mock 接口 {@link ZhiflowPayApi}；{@link MallConfigProperties} 直接 new（mock 具体类会污染 TypeCache）。
 */
class WxPayChannelTest {

	private ZhiflowPayApi payApi;

	private WxPayChannel channel;

	@BeforeEach
	void setUp() {
		payApi = mock(ZhiflowPayApi.class);
		MallConfigProperties props = new MallConfigProperties();
		props.setNotifyHost("https://shop.example.com");
		channel = new WxPayChannel(payApi, props);
	}

	@Test
	@DisplayName("公众号内走 JSAPI：带 openid 下单，packageValue 还原成 JSSDK 的 package")
	void jsapiInsideWechat() {
		when(payApi.unifiedOrderWx(any(), anyString())).thenReturn(R.ok("{\"appId\":\"wxA\",\"timeStamp\":\"1\","
				+ "\"nonceStr\":\"n\",\"packageValue\":\"prepay_id=P1\",\"signType\":\"MD5\",\"paySign\":\"S\"}"));

		PaymentIntent intent = channel.createPayment(ctx("H5-WX", "openid-1", null), payOrder("12.34"));

		ArgumentCaptor<WxUnifiedOrderReq> req = ArgumentCaptor.forClass(WxUnifiedOrderReq.class);
		verify(payApi).unifiedOrderWx(req.capture(), anyString());
		assertEquals("JSAPI", req.getValue().getTradeType());
		assertEquals(1234, req.getValue().getTotalFee());
		assertEquals("openid-1", req.getValue().getSubOpenid());
		assertEquals("https://shop.example.com/mallapi/paynotify/1/t1", req.getValue().getNotifyUrl());
		assertEquals(PaymentIntent.Type.SDK, intent.getType());
		assertEquals("prepay_id=P1", intent.getPayParams().get("package"));
		assertEquals("S", intent.getPayParams().get("paySign"));
	}

	@Test
	@DisplayName("公众号内缺 openid 直接拒绝，不去渠道下单")
	void jsapiWithoutOpenidIsRejected() {
		assertThrows(IllegalStateException.class, () -> channel.createPayment(ctx("H5-WX", null, null), payOrder("1")));
	}

	@Test
	@DisplayName("普通浏览器走 MWEB：返回跳转地址，带上编码后的回跳地址")
	void mwebInBrowser() {
		when(payApi.unifiedOrderWx(any(), anyString()))
				.thenReturn(R.ok("{\"mwebUrl\":\"https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=P2\"}"));

		PaymentIntent intent = channel.createPayment(ctx("H5", null, "https://shop.example.com/pay/result?id=1"),
				payOrder("0.01"));

		assertEquals(PaymentIntent.Type.REDIRECT, intent.getType());
		assertEquals("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=P2"
				+ "&redirect_url=https%3A%2F%2Fshop.example.com%2Fpay%2Fresult%3Fid%3D1", intent.getRedirectUrl());
	}

	@Test
	@DisplayName("下单失败抛异常，不返回半成品")
	void unifiedOrderFailure() {
		when(payApi.unifiedOrderWx(any(), anyString())).thenReturn(R.failed("商户号未开通 H5 支付"));
		assertThrows(IllegalStateException.class, () -> channel.createPayment(ctx("H5", null, null), payOrder("1")));
	}

	@Test
	@DisplayName("查单：SUCCESS 已付（分转元）、CLOSED 失败、NOTPAY 未付、查不通 UNKNOWN")
	void queryMapping() {
		WxOrderQueryDTO paid = new WxOrderQueryDTO();
		paid.setTradeState("SUCCESS");
		paid.setTotalFee(1234);
		paid.setTransactionId("T1");
		paid.setTimeEnd("20261020123000");
		when(payApi.queryOrderWx(any(), anyString())).thenReturn(R.ok(paid));
		PaymentQueryResult r = channel.query(payOrder("12.34"));
		assertEquals(PaymentQueryResult.State.PAID, r.getState());
		assertEquals(new BigDecimal("12.34"), r.getPaidAmount());
		assertEquals("T1", r.getChannelTxnId());
		assertEquals(LocalDateTime.of(2026, 10, 20, 12, 30), r.getPayTime());

		assertEquals(PaymentQueryResult.State.FAILED, queryWith("CLOSED").getState());
		assertEquals(PaymentQueryResult.State.FAILED, queryWith("PAYERROR").getState());
		assertEquals(PaymentQueryResult.State.NOT_PAID, queryWith("NOTPAY").getState());

		when(payApi.queryOrderWx(any(), anyString())).thenReturn(R.failed("ORDERNOTEXIST"));
		assertEquals(PaymentQueryResult.State.UNKNOWN, channel.query(payOrder("1")).getState());
		when(payApi.queryOrderWx(any(), anyString())).thenThrow(new RuntimeException("timeout"));
		assertEquals(PaymentQueryResult.State.UNKNOWN, channel.query(payOrder("1")).getState());
	}

	@Test
	@DisplayName("回调验签通过：按微信交易号幂等，应答 XML SUCCESS")
	void callbackPaid() {
		WxOrderNotifyDTO n = new WxOrderNotifyDTO();
		n.setOutTradeNo("PAY1");
		n.setTransactionId("T1");
		n.setTotalFee(100);
		when(payApi.notifyOrderWx(anyString(), anyString())).thenReturn(R.ok(n));

		CallbackResult r = channel.parseCallback(RawCallback.builder().tenantId("t1").body("<xml/>").build());

		assertTrue(r.isSignValid());
		assertEquals(CallbackResult.Event.PAID, r.getEvent());
		assertEquals("PAY1", r.getPayNo());
		assertEquals("T1", r.getIdempotentKey());
		assertEquals(new BigDecimal("1.00"), r.getAmount());
		assertEquals("text/xml", r.getAckContentType());
		assertTrue(r.getAckBody().contains("SUCCESS"));
	}

	@Test
	@DisplayName("回调验签失败 / 空报文 / pay-admin 异常：一律判签名无效")
	void callbackInvalid() {
		assertFalse(channel.parseCallback(RawCallback.builder().body(" ").build()).isSignValid());
		when(payApi.notifyOrderWx(anyString(), anyString())).thenReturn(R.failed("签名错误"));
		assertFalse(channel.parseCallback(RawCallback.builder().body("<xml/>").build()).isSignValid());
		when(payApi.notifyOrderWx(anyString(), anyString())).thenThrow(new RuntimeException("down"));
		assertFalse(channel.parseCallback(RawCallback.builder().body("<xml/>").build()).isSignValid());
	}

	@Test
	@DisplayName("金额换算：多于两位小数直接拒绝，不截断")
	void fenConversion() {
		assertEquals(1, WxPayChannel.toFen(new BigDecimal("0.01")));
		assertThrows(ArithmeticException.class, () -> WxPayChannel.toFen(new BigDecimal("0.015")));
		assertNull(WxPayChannel.fromFen(null));
		assertNull(WxPayChannel.parseTimeEnd("bad"));
	}

	private PaymentQueryResult queryWith(String state) {
		WxOrderQueryDTO q = new WxOrderQueryDTO();
		q.setTradeState(state);
		when(payApi.queryOrderWx(any(), anyString())).thenReturn(R.ok(q));
		return channel.query(payOrder("1"));
	}

	static PaymentContext ctx(String appType, String openId, String returnUrl) {
		return PaymentContext.builder()
				.tenantId("t1")
				.appType(appType)
				.appId("wxA")
				.payOpenId(openId)
				.returnUrl(returnUrl)
				.clientIp("127.0.0.1")
				.subject("测试商品")
				.build();
	}

	static PayOrder payOrder(String amount) {
		PayOrder p = new PayOrder();
		p.setPayNo("PAY1");
		p.setOrderNo("O1");
		p.setAmount(new BigDecimal(amount));
		return p;
	}
}
