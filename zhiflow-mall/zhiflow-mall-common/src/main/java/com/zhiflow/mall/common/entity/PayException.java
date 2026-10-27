package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 差错池（支付与订单状态机 v1.2 第六章）。
 *
 * <p>「钱的事实必须有落点」：任何一笔渠道确认了、但正常路径进不了账的钱，先落这张表，
 * 再由规则或人处置。打 ERROR 日志不算落点。
 *
 * <p>刻意<b>不</b>继承 {@code TenantEntity}：本表没有 {@code del_flag}（差错记录只处置、不删除），
 * 时间列是 {@code timestamptz}（约定：新表一律 timestamptz），对应 {@link OffsetDateTime}。
 * pgjdbc 不支持把 timestamptz 读成 {@code LocalDateTime}，所以这里不能沿用 BaseEntity 的时间字段。
 *
 * <p>{@code dedupe_key} 唯一：同一笔钱可能被回调、补单任务、对账三条路径先后发现，只入池一次。
 */
@Data
@TableName("pay_exception")
@Schema(description = "差错池")
public class PayException {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** {@link com.zhiflow.mall.common.trade.payexception.PayExceptionType} */
    private String type;

    /** {@link com.zhiflow.mall.common.trade.payexception.PayExceptionStatus} */
    private String status;

    private String orderId;

    private String orderNo;

    private String payOrderId;

    /** 渠道码，与 pay_config.type 同值（1=微信，2=支付宝…） */
    private String channel;

    private String channelTxnId;

    /** 渠道确认的金额，最小单位（分 / 仙） */
    private Long amountMinor;

    /** ISO 4217 字母码（CNY / HKD） */
    private String currency;

    private String callbackLogId;

    private String dedupeKey;

    /** 入池时的上下文，给人工处置看 */
    private String detail;

    private String resolution;

    private String resolvedBy;

    private OffsetDateTime resolvedTime;

    private String resolveReason;

    private String tenantId;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;
}
