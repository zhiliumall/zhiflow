package com.zhiflow.mall.api.payment.channel;

import com.zhiflow.api.pay.ZhiflowPayApi;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.api.payment.dto.CallbackResult;
import com.zhiflow.mall.api.payment.dto.PaymentIntent;
import com.zhiflow.mall.api.payment.dto.PaymentQueryResult;
import com.zhiflow.mall.api.payment.dto.RawCallback;
import com.zhiflow.mall.common.config.MallConfigProperties;
import com.zhiflow.pay.common.dto.inside.AliOrderNotifyDTO;
import com.zhiflow.pay.common.dto.inside.AliOrderQueryDTO;
import com.zhiflow.pay.common.dto.inside.AliUnifiedOrderReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static com.zhiflow.mall.api.payment.channel.WxPayChannelTest.ctx;
import static com.zhiflow.mall.api.payment.channel.WxPayChannelTest.payOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 国内支付宝手机网站支付渠道：表单转跳转地址、查单状态映射、回调解析。
 */
class AliPayChannelTest {

	/** easysdk 手机网站支付的真实形态：action 里已带查询串（&amp; 转义），隐藏字段的 value 也是转义过的 JSON。 */
	private static final String FORM = "<form name=\"punchout_form\" method=\"post\" "
			+ "action=\"https://openapi.alipay.com/gateway.do?charset=UTF-8&amp;method=alipay.trade.wap.pay&amp;sign=a%2Bb\">\n"
			+ "<input type=\"hidden\" name=\"biz_content\" value=\"{&quot;out_trade_no&quot;:&quot;PAY1&quot;}\">\n"
			+ "<input type=\"submit\" value=\"立即支付\" style=\"display:none\" >\n"
			+ "</form>\n<script>document.forms[0].submit();</script>";

	private ZhiflowPayApi payApi;

	private AliPayChannel channel;

	@BeforeEach
	void setUp() {
		payApi = mock(ZhiflowPayApi.class);
		MallConfigProperties props = new MallConfigProperties();
		props.setNotifyHost("https://shop.example.com");
		channel = new AliPayChannel(payApi, props);
	}

	@Test
	@DisplayName("表单转跳转地址：反转义 action，隐藏字段 URL 编码后追加，没有 name 的提交按钮不进地址")
	void formToUrl() {
		assertEquals("https://openapi.alipay.com/gateway.do?charset=UTF-8&method=alipay.trade.wap.pay&sign=a%2Bb"
				+ "&biz_content=%7B%22out_trade_no%22%3A%22PAY1%22%7D", AliPayChannel.formToUrl(FORM));
	}

	@Test
	@DisplayName("表单缺失或没有 action：抛异常，不给前端一个坏地址")
	void formToUrlRejectsBrokenForm() {
		assertThrows(IllegalStateException.class, () -> AliPayChannel.formToUrl(" "));
		assertThrows(IllegalStateException.class, () -> AliPayChannel.formToUrl("<form method=\"post\"></form>"));
	}

	@Test
	@DisplayName("下单：MWEB、元为单位、回调地址带渠道码与租户；没传退出地址时用回跳地址")
	void createPayment() {
		when(payApi.unifiedOrderAli(any(), anyString()))
				.thenReturn(R.ok(cn.hutool.json.JSONUtil.toJsonStr(Map.of("body", FORM))));

		PaymentIntent intent = channel.createPayment(ctx("H5", null, "https://shop.example.com/pay/result"),
				payOrder("12.34"));

		ArgumentCaptor<AliUnifiedOrderReq> req = ArgumentCaptor.forClass(AliUnifiedOrderReq.class);
		verify(payApi).unifiedOrderAli(req.capture(), anyString());
		assertEquals("MWEB", req.getValue().getTradeType());
		assertEquals(new BigDecimal("12.34"), req.getValue().getTotalAmount());
		assertEquals("https://shop.example.com/mallapi/paynotify/2/t1", req.getValue().getNotifyUrl());
		assertEquals("https://shop.example.com/pay/result", req.getValue().getQuitUrl());
		assertEquals(PaymentIntent.Type.REDIRECT, intent.getType());
		assertTrue(intent.getRedirectUrl().startsWith("https://openapi.alipay.com/gateway.do?"));
	}

	@Test
	@DisplayName("查单：TRADE_SUCCESS / FINISHED 已付、TRADE_CLOSED 失败、交易不存在算未付、查不通 UNKNOWN")
	void queryMapping() {
		AliOrderQueryDTO paid = new AliOrderQueryDTO();
		paid.setTradeStatus("TRADE_SUCCESS");
		paid.setTradeNo("A1");
		paid.setTotalAmount(new BigDecimal("12.34"));
		paid.setSendPayDate("2026-10-20 12:30:00");
		when(payApi.queryOrderAli(any(), anyString())).thenReturn(R.ok(paid));
		PaymentQueryResult r = channel.query(payOrder("12.34"));
		assertEquals(PaymentQueryResult.State.PAID, r.getState());
		assertEquals("A1", r.getChannelTxnId());

		assertEquals(PaymentQueryResult.State.PAID, queryWith("TRADE_FINISHED").getState());
		assertEquals(PaymentQueryResult.State.FAILED, queryWith("TRADE_CLOSED").getState());
		assertEquals(PaymentQueryResult.State.NOT_PAID, queryWith("WAIT_BUYER_PAY").getState());

		AliOrderQueryDTO missing = new AliOrderQueryDTO();
		missing.setSubCode("ACQ.TRADE_NOT_EXIST");
		when(payApi.queryOrderAli(any(), anyString())).thenReturn(R.ok(missing));
		assertEquals(PaymentQueryResult.State.NOT_PAID, channel.query(payOrder("1")).getState());

		when(payApi.queryOrderAli(any(), anyString())).thenReturn(R.failed("isv.invalid-app-id"));
		assertEquals(PaymentQueryResult.State.UNKNOWN, channel.query(payOrder("1")).getState());
	}

	@Test
	@DisplayName("回调：同一笔交易的 TRADE_SUCCESS 与 TRADE_FINISHED 各占一个幂等位，应答纯文本 success")
	void callbackPaid() {
		AliOrderNotifyDTO n = new AliOrderNotifyDTO();
		n.setOutTradeNo("PAY1");
		n.setTradeNo("A1");
		n.setTradeStatus("TRADE_SUCCESS");
		n.setTotalAmount(new BigDecimal("1.00"));
		when(payApi.notifyOrderAli(anyMap(), anyString())).thenReturn(R.ok(n));

		CallbackResult r = channel.parseCallback(RawCallback.builder().tenantId("t1").params(Map.of("k", "v")).build());

		assertTrue(r.isSignValid());
		assertEquals(CallbackResult.Event.PAID, r.getEvent());
		assertEquals("A1:TRADE_SUCCESS", r.getIdempotentKey());
		assertEquals("success", r.getAckBody());

		n.setTradeStatus("TRADE_CLOSED");
		assertEquals(CallbackResult.Event.FAILED, channel.parseCallback(
				RawCallback.builder().params(Map.of("k", "v")).build()).getEvent());
	}

	@Test
	@DisplayName("回调验签失败 / 无参数：判签名无效")
	void callbackInvalid() {
		assertFalse(channel.parseCallback(RawCallback.builder().params(Map.of()).build()).isSignValid());
		when(payApi.notifyOrderAli(anyMap(), anyString())).thenReturn(R.failed("app_id 不匹配"));
		assertFalse(channel.parseCallback(RawCallback.builder().params(Map.of("k", "v")).build()).isSignValid());
	}

	private PaymentQueryResult queryWith(String status) {
		AliOrderQueryDTO q = new AliOrderQueryDTO();
		q.setTradeStatus(status);
		when(payApi.queryOrderAli(any(), anyString())).thenReturn(R.ok(q));
		return channel.query(payOrder("1"));
	}
}
