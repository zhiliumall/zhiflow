package com.zhiflow.pay.common.dto.inside;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 支付宝（国内）统一下单的<b>请求投影</b>，取代契约参数里的
 * {@code AliBaseRequest}（它的 {@code params} 是 {@code Map<String, Object>}）。
 *
 * <p>为什么必须按方法拆开：{@code AliBaseRequest} 一个壳被 5 个方法共用，而每个方法往
 * {@code params} 里塞的 key 集合<b>完全不同</b>（下单要 tradeType/notifyUrl/outTradeNo/
 * totalAmount/returnUrl/quitUrl/subject；查单只要 outTradeNo）。
 * {@code Map<String, Object>} 在 proto 里写不出来（值类型不可描述），而硬翻成
 * {@code map<string, string>} 会把「哪个方法需要哪些 key」这件事继续藏着。
 *
 * <p>字段取自 5 个活调用点实际 put 的 key 的并集，服务端在 {@code AliPayController} 内部
 * 用<b>同名 key</b> 装回那张 map，再走原有分支——包括「key 缺失时 {@code .toString()} 抛 NPE」
 * 这一点也逐字保留（例如 QRCODE 分支不读 returnUrl/quitUrl，而 NATIVE 分支读）。
 */
@Data
public class AliUnifiedOrderReq {

	/** ISV 代商户调用的 app_auth_token。普通模式下服务端会把它置空。 */
	private String appAuthToken;

	/** 交易类型 NATIVE（电脑网页）/APP/MWEB（手机网站）/QRCODE（扫码）。 */
	private String tradeType;

	/** 商户订单号。 */
	private String outTradeNo;

	/** 订单金额，单位<b>元</b>（支付宝协议本来就是元制十进制串，见 {@link AliOrderQueryDTO}）。 */
	private BigDecimal totalAmount;

	/** 支付结果回调地址。 */
	private String notifyUrl;

	/** 同步返回地址。NATIVE / MWEB 分支必需；QRCODE 分支不读。 */
	private String returnUrl;

	/** 中途退出地址。MWEB 分支必需；其余分支不读。 */
	private String quitUrl;

	/** 订单标题。 */
	private String subject;

}
