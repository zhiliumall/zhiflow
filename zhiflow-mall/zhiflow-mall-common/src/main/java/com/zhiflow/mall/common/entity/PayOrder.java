/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import com.zhiflow.common.core.mybatis.typehandler.JsonbTypeHandler;
import org.apache.ibatis.type.JdbcType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付单
 *
 * <p>本仓此前没有这层实体——直接拿 {@code order_no} 当渠道 {@code out_trade_no}。
 * 而后台改价会**重签 order_no**，历史上因此造成过回调与对账落空。
 * 新框架下 {@link #payNo} 一次生成、永不变，渠道只认它；订单号只是关联字段。
 *
 * <p>落位：租户 schema（与 {@code order_info} 同处），不是 public。理由见
 * {@code db/2026-09-07_payment_platform_tables.sql} 头部注释。
 *
 * @date 2026-09-07
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pay_order")
@Schema(description = "支付单")
public class PayOrder extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 支付单号 = 渠道 out_trade_no。**一次生成、永不变**。
     * <p>与 {@code order_no} 解耦是这张表存在的主要理由，改动前先读类注释。
     */
    @Schema(description = "支付单号（渠道 out_trade_no，不可变）")
    private String payNo;

    @Schema(description = "关联业务订单号")
    private String orderNo;

    /**
     * 业务类型，取值见 {@code PayBizTypeEnum}：
     * ORDER / VIP_RECHARGE / BALANCE / CASHIER / DEPOSIT。
     */
    @Schema(description = "业务类型")
    private String bizType;

    @Schema(description = "业务单ID")
    private String bizId;

    /**
     * 渠道码，与 {@code pay_config.type} 同值
     * （1微信 2支付宝 3余额）。
     */
    @Schema(description = "渠道码")
    private String channelCode;

    /**
     * 支付金额。{@code numeric(10,2)}，与 mall 全线金额列一致。
     */
    @Schema(description = "支付金额")
    private BigDecimal amount;

    @Schema(description = "币种（CNY/HKD/USD…）")
    private String currCode;

    @Schema(description = "渠道流水号（PayRef / transaction_id）")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String channelTxnId;

    /**
     * 状态：CREATED / PAYING / PAID / FAILED / CANCELLED / PARTIAL_REFUNDED / REFUNDED（REFUNDING 不再使用）。
     * <p>只由支付单状态机写（{@code PayOrderStateMachine}，条件 UPDATE + 流水）。{@code updateStrategy = NEVER}：
     * updateById / update(entity, wrapper) 整行写回时不会带出本列（第一层）；本组其余由状态机维护的列同理。
     */
    @Schema(description = "状态")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String status;


    @Schema(description = "支付成功时间")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime payTime;

    @Schema(description = "支付单过期时间")
    private LocalDateTime expireTime;

    @Schema(description = "发起支付的客户端IP")
    private String clientIp;

    /**
     * 渠道私有上下文（jsonb）。各渠道自己需要回传的东西放这里，
     * 不要为单个渠道往这张表加列。
     */
    @Schema(description = "渠道私有上下文(JSON)")
    @TableField(typeHandler = JsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JSONObject attach;

    // ───────── 支付状态机 v1.2（V116）：支付域金额最小单位 + 币种 + 锁汇；状态附加标记 ─────────

    /** 收款币种 ISO 字母码（HKD / CNY）。{@link #currCode} 是历史列（老渠道写的是数字码 344），新代码以本列为准。 */
    @Schema(description = "收款币种(ISO 字母码)")
    private String currency;

    /** 收款金额（最小单位：分 / 仙），与 {@link #amount} 同一金额。 */
    @Schema(description = "收款金额(最小单位)")
    private Long amountMinor;








    @Schema(description = "渠道已关单")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Boolean channelClosed;

    @Schema(description = "迟到到账(P7)")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Boolean latePaid;

    @Schema(description = "重复付款(P9)")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Boolean duplicatePaid;

    @Schema(description = "失败原因(渠道给了才记)")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String failReason;

    @Schema(description = "本地关单时间")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private java.time.OffsetDateTime closedTime;

    /**
     * 进入当前状态的时刻。只由状态机的条件更新写（now()），不参与插入 / 整行更新；
     * 上线前已在当前状态的行为空，时效任务按状态取回退列（见 {@code StateSince}）。
     */
    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.time.OffsetDateTime stateSince;
}
