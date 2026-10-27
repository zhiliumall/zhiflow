package com.zhiflow.mall.api.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 向渠道查单的结果。
 *
 * <p><b>{@link State#UNKNOWN} 是一等公民，不是错误。</b> 渠道查不通时必须返回 UNKNOWN
 * 而不是「未支付」，也不要抛异常：
 * <ul>
 *   <li>返回「未支付」会让取消流程把一笔其实已付的订单取消掉并回滚库存——用户白付钱；</li>
 *   <li>抛异常会打断整条取消流程。</li>
 * </ul>
 * 调用方遇到 UNKNOWN 应当保守处理（不取消、留待对账收敛）。
 */
@Data
@Builder
public class PaymentQueryResult {

    private State state;

    /** 渠道流水号，退款要用。 */
    private String channelTxnId;

    /** 渠道侧实收金额，用于与本地支付单核对。 */
    private BigDecimal paidAmount;

    private String currCode;

    private LocalDateTime payTime;

    /** 渠道原始返回，排查用。 */
    private String rawMessage;

    public boolean isPaid() {
        return state == State.PAID;
    }

    public boolean isUnknown() {
        return state == State.UNKNOWN;
    }

    public static PaymentQueryResult unknown(String rawMessage) {
        return PaymentQueryResult.builder()
                .state(State.UNKNOWN)
                .rawMessage(rawMessage)
                .build();
    }

    public static PaymentQueryResult notPaid() {
        return PaymentQueryResult.builder().state(State.NOT_PAID).build();
    }

    public enum State {
        /** 渠道确认已收款。 */
        PAID,
        /** 渠道确认未收款（含用户放弃）。 */
        NOT_PAID,
        /** 渠道明确失败/拒付。 */
        FAILED,
        /** 查不通、超时、返回无法解析——**不可当作未支付**。 */
        UNKNOWN
    }
}
