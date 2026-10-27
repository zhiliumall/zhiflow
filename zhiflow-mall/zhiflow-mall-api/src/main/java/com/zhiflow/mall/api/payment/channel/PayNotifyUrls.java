package com.zhiflow.mall.api.payment.channel;

import com.zhiflow.mall.common.config.MallConfigProperties;
import com.zhiflow.mall.common.constant.MallConstants;

/**
 * 支付回调地址：{@code {notifyHost}/mallapi/paynotify/{channel}/{tenantId}}，由统一入口
 * {@code PaymentNotifyController} 接收。
 */
final class PayNotifyUrls {

	private PayNotifyUrls() {
	}

	static String of(MallConfigProperties props, String channelCode, String tenantId) {
		return props.getNotifyHost() + MallConstants.SERVER_ROUTE_NAME_MALLAPI + "/paynotify/" + channelCode + "/" + tenantId;
	}
}
