/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 支付回调留痕
 *
 * <p>每一条进来的渠道回调都落一行，**不论验签是否通过**——验签失败的报文恰恰是
 * 排查伪造攻击时最需要的证据。
 *
 * <p>{@code (channel_code, idempotent_key)} 上有唯一索引：回调重放会撞唯一键，
 * 这是幂等的第一道闸（第二道是「只更新还未支付的那一行」）。
 *
 * @date 2026-09-07
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pay_callback_log")
@Schema(description = "支付回调留痕")
public class PayCallbackLog extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 可能为空：报文解析失败或查无此单时仍要留痕。 */
    @Schema(description = "支付单号")
    private String payNo;

    @Schema(description = "渠道码")
    private String channelCode;

    /**
     * 幂等键：优先用渠道流水号，没有则用 {@code payNo + 事件类型}。
     * 与 {@code channelCode} 组唯一索引。
     */
    @Schema(description = "幂等键")
    private String idempotentKey;

    /**
     * 渠道原始报文，**脱敏后**留存（不落卡号/CVV）。
     * 留存年限待法务确认，建议 ≥5 年。
     */
    @Schema(description = "原始报文(脱敏)")
    private String rawBody;

    @Schema(description = "验签结果（1通过 0不通过）")
    private String signValid;

    @Schema(description = "渠道侧状态码（如微信 result_code）")
    private String channelStatus;

    /**
     * 处理结论：RECEIVED 收到 / ACCEPTED 已入账 / DUPLICATE 重复投递 /
     * REJECTED 被拒（验签失败、四项核对不过、查无此单）。
     */
    @Schema(description = "处理状态")
    private String processStatus;

    @Schema(description = "拒绝原因")
    private String rejectReason;
}
