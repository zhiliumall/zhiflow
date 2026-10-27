package com.zhiflow.pay.common.dto.inside;

import lombok.Data;

/**
 * 支付宝<b>分账</b>结果的跨服务投影，取代 SDK 的
 * {@code com.alipay.easysdk.util.generic.models.AlipayOpenApiGenericResponse}
 * （支付宝开放平台通用响应壳，不是分账专用类型）。
 *
 * <p>与微信那两条分账边<b>刻意不同口径</b>：微信侧服务端把 SDK 返回值直接丢弃、
 * {@code return R.ok()}（见 {@code WxPayController#profitSharing}），所以契约治成 {@code R<Void>}；
 * 支付宝侧服务端<b>确实</b>把响应放进了 {@code R.ok(resp)}，把它改成 {@code R<Void>} 等于在迁移里
 * 顺手删掉一段服务端真的产出的数据——那是行为变更。所以这里保留它的四个字段。
 *
 * <p>{@code httpBody}（整包原始响应）不进契约：0 调用方，且它把整个上游响应原样扩散出去。
 *
 * <p>⚠️ 本条边当前<b>零活调用点</b>（唯一引用在 mall-api 一段从未启用的分账草稿里，全被注释掉）。
 * 也就是说没有真实流量能验证这条边，复活分账功能时必须重新做一次端到端验证。
 */
@Data
public class AliProfitSharingDTO {

	/** 网关返回码，{@code 10000} 为成功。 */
	private String code;

	/** 网关返回描述。 */
	private String msg;

	/** 业务错误码，如 {@code ACQ.ROYALTY_RECEIVER_INVALID}。 */
	private String subCode;

	/** 业务错误描述。 */
	private String subMsg;

}
