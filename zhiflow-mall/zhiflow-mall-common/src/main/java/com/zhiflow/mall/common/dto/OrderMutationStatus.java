package com.zhiflow.mall.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单变更命令的只读恢复结果。
 *
 * <p>状态查询只按同一订单和 operationId 查状态流水，不会重新执行取消或自提。{@code known=false}
 * 表示服务端还没有看到对应流水，调用方必须保留 UNKNOWN 并交给后续对账，不能切换协议重放。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderMutationStatus {

	private String orderId;

	private String operationId;

	/** {@code CANCEL}。 */
	private String action;

	/** 当前订单状态机状态名；订单不存在时为 {@code NOT_FOUND}。 */
	private String state;

	/** 最近一条与 operationId 匹配的状态机事件。 */
	private String lastEvent;

	/** 状态机已写入目标事件。 */
	private boolean applied;

	/** 已能确定 operationId 是否落过状态流水。 */
	private boolean known;

}
