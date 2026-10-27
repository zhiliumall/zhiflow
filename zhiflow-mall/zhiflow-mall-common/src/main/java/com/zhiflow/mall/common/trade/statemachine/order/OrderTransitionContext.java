package com.zhiflow.mall.common.trade.statemachine.order;

import com.zhiflow.mall.common.trade.statemachine.Operator;
import lombok.Builder;

/**
 * 一次订单跳转的上下文。
 *
 * @param operatorType     触发方
 * @param operatorId       操作人（系统 / 渠道可空）
 * @param reason           原因（人工处理必填）
 * @param requestId        关联的回调留痕 / 请求链路
 * @param manualTarget     MANUAL_FIX 选的目标状态
 * @param patch            伴随列
 */
@Builder(toBuilder = true)
public record OrderTransitionContext(
		Operator operatorType,
		String operatorId,
		String reason,
		String requestId,
		OrderState manualTarget,
		OrderPatch patch) {

	public OrderPatch patchOrEmpty() {
		return patch == null ? OrderPatch.EMPTY : patch;
	}

	public static OrderTransitionContext system(String reason) {
		return OrderTransitionContext.builder().operatorType(Operator.SYSTEM).reason(reason).build();
	}
}
