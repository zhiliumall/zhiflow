package com.zhiflow.api.mall;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.common.dto.OrderMutationStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

/**
 * 订单信息服务 API 接口定义
 *
 */
@HttpExchange(url = "/mallapi", accept = "application/json", contentType = "application/json")
public interface OrderInfoApi {

	/**
	 * 取消商城订单。
	 *
	 * <p>服务端全部路径都是 {@code R.ok()}（无 data）或 {@code R.failed(code, msg)}，
	 * 唯一调用方（mall-admin 的 {@code OrderInfoController#orderCancel}）把 {@code R} 整个透传给前端、
	 * 不读 {@code data} —— 所以真实语义是 {@code R<Void>}，裸 {@code R} 只是没写全。
	 * 迁 gRPC 前先把它写实：裸 {@code R} 等价 {@code R<Object>}，proto 侧无从知道该建什么消息。
	 */
	@PutExchange("/orderinfo/inside/cancel/{id}")
	R<Void> orderCancelInside(@PathVariable("id") String id,
						@RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 查询订单取消/自提命令是否已经落入状态机流水。
	 *
	 * <p>这是只读恢复路径：它只按订单号和原 operationId 查流水，绝不重新执行写操作。
	 * gRPC 命令返回 UNKNOWN 时必须调用它或走同等业务对账，不能改走 REST 重放。</p>
	 */
	@GetExchange("/orderinfo/inside/mutation-status/{id}")
	R<OrderMutationStatus> mutationStatus(@PathVariable("id") String id,
			@RequestParam("operationId") String operationId,
			@RequestHeader(SecurityConstants.FROM) String from);
}
