package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 订单状态流水：每次跳转一行，谁、何时、从哪到哪、为什么、哪条规则放行。
 * 只追加，不改不删（无 del_flag）。
 */
@Data
@TableName("order_state_log")
public class OrderStateLog {

	@TableId(type = IdType.ASSIGN_ID)
	private String id;

	private String orderId;

	/** 待付款记 PENDING_PAY，非法组合记 LEGACY_INVALID */
	private String fromStatus;

	private String toStatus;

	private String event;

	private String ruleId;

	private String operatorType;

	private String operatorId;

	private String reason;

	private String requestId;

	private String tenantId;

	private OffsetDateTime createTime;
}
