package com.zhiflow.pay.common.dto.inside;

import lombok.Data;

/**
 * 微信支付（国内）<b>支付回调</b>解析结果的跨服务投影，取代契约里的 SDK 类型
 * {@code com.github.binarywang.wxpay.bean.notify.WxPayOrderNotifyResult}。
 *
 * <p>SDK 那个类有 20+ 个字段（含 {@code returnCode/sign/xmlString} 等协议层字段与
 * {@code couponList} 嵌套列表）。2026-09-14 逐点核过的 6 个活调用点
 * （VipUserRecharge / UserBalanceRecord / OrderInfo / OrderCashier / ShopDeposit /
 * SysTenantPackageRecharge 的 {@code notify-wx} 入口）<b>逐字同构</b>，只读下面 5 个字段。
 *
 * <p>{@code attach} 是第 6 个：0 个调用点读，但它属于「回调流水号 / 业务标识」一类，
 * 按「单号 / 金额 / 状态 / 流水号宁可多留」保留。
 */
@Data
public class WxOrderNotifyDTO {

	/** 商户订单号。6/6 调用点用它查单。 */
	private String outTradeNo;

	/** 交易类型 JSAPI/NATIVE/APP/MWEB。6/6 落库回显。 */
	private String tradeType;

	/**
	 * 订单总金额，单位<b>分</b>。
	 *
	 * <p>刻意保留 SDK 的 {@code Integer} 分，<b>不</b>在边界换算成元：调用方今天做的是
	 * {@code paymentPrice.multiply(100).intValue() == totalFee} 这种整数比较，换成元制
	 * {@code BigDecimal.compareTo} 会在标度 &gt; 2 的价格上给出<b>不同的结论</b>
	 * （10.005 元：今天 intValue 截断成 1000 判等，元制比较则不等）。
	 * 那是资金核对闸上的行为变化，不属于恒等迁移。分是整数、proto 用 {@code int32} 表达精确无损，
	 * 与「金额禁 double」并不冲突。统一元制口径留作独立任务。
	 */
	private Integer totalFee;

	/** 支付完成时间，微信原始格式 {@code yyyyMMddHHmmss}。解析责任<b>留在调用方</b>（今天就是它在 parse）。 */
	private String timeEnd;

	/** 微信支付流水号。6/6 落库，退款与对账的锚点。 */
	private String transactionId;

	/** 商家数据包。0 个调用点读，按「业务标识类字段宁可多留」保留。 */
	private String attach;

}
