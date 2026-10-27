package com.zhiflow.pay.common.dto.inside;

import lombok.Data;

import java.util.List;

/**
 * 支付宝分账的<b>请求投影</b>。理由同 {@link AliUnifiedOrderReq}——而且这一条更必要：
 * 它的 {@code params} 里嵌着 {@code List<Map<String, Object>>}（royalty_parameters）
 * 与 {@code Map<String, Object>}（extend_params），是全批最深的一处弱类型。
 *
 * <p>⚠️ 本条边当前<b>零活调用点</b>（唯一引用是 mall-api 里一段被注释掉的分账草稿）。
 * 字段取自那段草稿实际 put 的 key。服务端把它装回 {@code alipay.trade.order.settle} 要求的
 * snake_case biz params，其中 {@code royaltyFinish} 落进 {@code extend_params.royalty_finish}
 * （支付宝没有独立的「分账完结」接口，靠这个标识在本次分账后解冻剩余资金）。
 */
@Data
public class AliProfitSharingReq {

	/** ISV 代商户调用的 app_auth_token。普通模式下服务端会把它置空。 */
	private String appAuthToken;

	/** 分账请求号（对应 {@code out_request_no}）。 */
	private String outRequestNo;

	/** 支付宝交易号（对应 {@code trade_no}）。 */
	private String tradeNo;

	/** 分账接收方列表。 */
	private List<AliRoyaltyParameter> royaltyParameters;

	/** 分账完结标识，取值 {@code "true"}；落进 {@code extend_params.royalty_finish}。 */
	private String royaltyFinish;

}
