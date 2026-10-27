package com.zhiflow.pay.common.dto.inside;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 支付宝分账的一个接收方（{@code royalty_parameters} 的一项）。
 *
 * <p>字段名是本仓的驼峰写法，服务端装回支付宝要求的 snake_case key
 * （{@code trans_in_type / trans_in / trans_in_name / amount / royalty_scene}）。
 * 零活调用点，见 {@link AliProfitSharingReq}。
 */
@Data
public class AliRoyaltyParameter {

	/** 接收方类型（对应 {@code trans_in_type}）。 */
	private String transInType;

	/** 接收方账号（对应 {@code trans_in}）。 */
	private String transIn;

	/** 接收方名称（对应 {@code trans_in_name}）。 */
	private String transInName;

	/** 分账金额，单位<b>元</b>。 */
	private BigDecimal amount;

	/** 分账场景描述（对应 {@code royalty_scene}）。 */
	private String royaltyScene;

}
