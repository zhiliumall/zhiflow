package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 支付单状态流水：P2–P9 每次跳转一行；回调驱动的跳转带 callback_log_id 反查原报文。
 * 只追加，不改不删（无 del_flag）。
 */
@Data
@TableName("pay_order_state_log")
public class PayOrderStateLog {

	@TableId(type = IdType.ASSIGN_ID)
	private String id;

	private String payOrderId;

	private String payNo;

	private String fromStatus;

	private String toStatus;

	private String event;

	private String ruleId;

	private String operatorType;

	private String channelTxnId;

	private String callbackLogId;

	private String reason;

	/**
	 * 写入路径显式 set 为准；漏 set 时由 {@code ZhiflowMetaObjectHandler} 按当前租户上下文兜底。
	 * 本表不在 {@code base.tenant.tables} 名单（租户拦截器不补 tenant_id），库里 tenant_id 又是 NOT NULL 且无默认值，
	 * 缺这个注解时漏 set 的 MP insert 直接撞约束（与 2026-09-30 搜索干预 #15 同形）。
	 * 只作用于 MP 生成的 insert；手写 INSERT SQL 仍须自己绑定 tenant_id。
	 */
	@TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
	private String tenantId;

	private OffsetDateTime createTime;
}
