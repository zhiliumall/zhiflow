/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.pay.admin.controller.ali;

import com.alipay.easysdk.factory.MultipleFactory;
import com.alipay.easysdk.payment.common.Client;
import com.zhiflow.common.core.util.R;
import com.zhiflow.pay.admin.config.AliPayConfiguration;
import com.zhiflow.pay.admin.service.PayConfigService;
import com.zhiflow.pay.common.constant.PayConstants;
import com.zhiflow.pay.common.dto.inside.AliOrderNotifyDTO;
import com.zhiflow.pay.common.entity.PayConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 支付宝回调验签：回调入口交给 {@link AliPayController#notifyOrder} 的是只读 Map，
 * 而 easysdk 验签会从入参里 remove 掉 sign / sign_type，控制器必须传可变副本给 SDK。
 */
class AliPayNotifyVerifyTest {

	@Test
	void readOnlyCallbackParamsStillVerify() throws Exception {
		PayConfig cfg = new PayConfig();
		cfg.setType(PayConstants.PAY_TYPE_2);
		cfg.setAppId("2021000000000001");
		cfg.setMchId("2088000000000001");
		PayConfigService payConfigService = mock(PayConfigService.class);
		when(payConfigService.findByType(PayConstants.PAY_TYPE_2)).thenReturn(cfg);

		Client common = mock(Client.class);
		// 与 easysdk Signer.verifyParams 一致：先取出 sign，再从入参里删掉 sign、sign_type
		when(common.verifyNotify(anyMap())).thenAnswer(inv -> {
			Map<String, String> p = inv.getArgument(0);
			p.remove("sign");
			p.remove("sign_type");
			return true;
		});
		MultipleFactory factory = mock(MultipleFactory.class);
		when(factory.Common()).thenReturn(common);

		Map<String, String> raw = new HashMap<>();
		raw.put("app_id", "2021000000000001");
		raw.put("seller_id", "2088000000000001");
		raw.put("out_trade_no", "P1");
		raw.put("trade_no", "T1");
		raw.put("trade_status", "TRADE_SUCCESS");
		raw.put("total_amount", "79.00");
		raw.put("sign", "x");
		raw.put("sign_type", "RSA2");
		Map<String, String> readOnly = Collections.unmodifiableMap(raw);

		try (MockedStatic<AliPayConfiguration> st = mockStatic(AliPayConfiguration.class)) {
			st.when(AliPayConfiguration::getFactory).thenReturn(factory);
			R<AliOrderNotifyDTO> r = new AliPayController(payConfigService, null).notifyOrder(readOnly);
			assertTrue(r.isOk(), "只读入参也应验签通过，实际：" + r.getMsg());
			assertEquals("TRADE_SUCCESS", r.getData().getTradeStatus());
			assertEquals("P1", r.getData().getOutTradeNo());
		}
		// 调用方的入参不能被改动
		assertEquals("x", readOnly.get("sign"));
	}
}
