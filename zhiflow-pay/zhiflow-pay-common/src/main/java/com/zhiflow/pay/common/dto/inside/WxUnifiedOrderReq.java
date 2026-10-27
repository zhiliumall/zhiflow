package com.zhiflow.pay.common.dto.inside;

import lombok.Data;

/**
 * 微信支付（国内）统一下单的<b>请求投影</b>，取代契约参数里的第三方 SDK 类型
 * {@code com.github.binarywang.wxpay.bean.request.WxPayUnifiedOrderRequest}。
 *
 * <p>「整个请求体就是第三方 jar 里的类」本身就是一条债（门禁基线里的
 * {@code ZhiflowPayApi#unifiedOrderWx(request) SDK}）：SDK 升个版本，契约的形状就跟着变，
 * 而且 proto 里写不出来——那个类有 40+ 个字段、还带 XStream 注解与嵌套 {@code SceneInfo}。
 *
 * <p>字段是 2026-09-14 把 <b>5 个活调用点全部 {@code setXxx} 求并集</b>得到的（逐点核过：
 * OrderInfoController / VipUserRechargeController / ShopDepositController /
 * OrderCashierController / SysTenantPackageRechargeController）。服务端在
 * {@code WxPayController} 内部把它装回 SDK 请求对象，{@code productId}
 * 仍由服务端按 tradeType 自行设置。
 */
@Data
public class WxUnifiedOrderReq {

	/** 服务商/普通模式下的 appid。 */
	private String appid;

	/** 子商户 appid（服务商模式）。 */
	private String subAppId;

	/** 子商户号（服务商模式）。普通模式下服务端会把它清空。 */
	private String subMchId;

	/** 子商户 openid（JSAPI）。 */
	private String subOpenid;

	/** 商品描述。 */
	private String body;

	/** 商户订单号。 */
	private String outTradeNo;

	/** 订单总金额，单位<b>分</b>。口径见 {@link WxOrderNotifyDTO#getTotalFee()}。 */
	private Integer totalFee;

	/** 交易类型 JSAPI/NATIVE/APP/MWEB。 */
	private String tradeType;

	/** 支付结果回调地址。 */
	private String notifyUrl;

	/** 终端 IP。 */
	private String spbillCreateIp;

}
