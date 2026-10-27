package com.zhiflow.mall.admin.service;

import com.zhiflow.api.mall.OrderInfoApi;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.common.dto.OrderMutationStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * 订单取消/自提命令的 UNKNOWN 收敛器。
 *
 * <p>只读查询同一 operationId；确认状态机流水已落地才把 UNKNOWN 收敛为成功。状态未确认时原样返回，
 * 不切换协议、不重新执行写命令。</p>
 */
@Slf4j
public final class OrderMutationRecovery {

	private OrderMutationRecovery() {
	}

	public static R<Void> recover(R<Void> result, OrderInfoApi api, String orderId, String operationId,
			String from) {
		if (!isUnknown(result)) {
			return result;
		}
		try {
			R<OrderMutationStatus> status = api.mutationStatus(orderId, operationId, from);
			OrderMutationStatus data = status == null ? null : status.getData();
			if (status != null && status.getCode() == 0 && data != null && data.isKnown() && data.isApplied()) {
				log.info("[order-mutation] UNKNOWN 已由状态查询收敛 orderId={} operationId={} action={}", orderId,
						operationId, data.getAction());
				return R.ok();
			}
		}
		catch (RuntimeException ex) {
			log.warn("[order-mutation] UNKNOWN 状态查询失败，保留 UNKNOWN orderId={} operationId={}", orderId,
					operationId, ex);
		}
		return result == null ? unknownResult() : result;
	}

	private static boolean isUnknown(R<?> result) {
		return result == null;
	}

	private static R<Void> unknownResult() {
		return R.failed("内部命令结果未知，请按业务流水查询");
	}
}
