package com.zhiflow.pay.common.dto.inside;

import lombok.Data;

/**
 * 支付宝（国内）查单的<b>请求投影</b>。理由同 {@link AliUnifiedOrderReq}。
 * 4 个活调用点只放 {@code outTradeNo} 一个 key。
 */
@Data
public class AliOrderQueryReq {

	/** ISV 代商户调用的 app_auth_token。普通模式下服务端会把它置空。 */
	private String appAuthToken;

	/** 商户订单号。 */
	private String outTradeNo;

}
