package com.zhiflow.mall.api.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 渠道回调的解析结果（渠道实现 → 编排层）。
 *
 * <p>渠道实现只负责**验签 + 解析**，一律不碰任何业务状态。入账由编排层统一做，
 * 这样「验签通过就入账」这条最危险的捷径在结构上就走不通。
 *
 * <p>编排层拿到本对象后还要过四道核对（商户号、payNo、金额、币种）和一次渠道回查，
 * 全过才置已支付。
 */
@Data
@Builder
public class CallbackResult {

    /** 验签是否通过。false ⇒ 编排层直接丢弃并告警，订单状态不动。 */
    private boolean signValid;

    /** 我方支付单号（渠道回传的 orderRef / out_trade_no）。 */
    private String payNo;

    /** 渠道流水号。 */
    private String channelTxnId;

    /**
     * 幂等键。优先用渠道流水号；渠道不给就用 {@code payNo + 事件类型}。
     * 落 {@code pay_callback_log} 的唯一索引，重放会被挡。
     */
    private String idempotentKey;

    /** 渠道声明的支付结果。 */
    private Event event;

    /** 渠道声明的实收金额，用于与本地支付单核对。 */
    private BigDecimal amount;

    private String currCode;

    /** 渠道声明的商户号，用于核对是不是打给我们的。 */
    private String merchantId;

    /** 渠道侧原始状态码，留痕用。 */
    private String channelStatus;

    /**
     * 处理完成后要回给渠道的应答体。各渠道约定不同
     * （支付宝要纯文本 {@code success}，微信要特定 XML/JSON），由渠道实现给出。
     * <p>回错会导致渠道判定投递失败并反复重推。
     */
    private String ackBody;

    /** 应答体的 Content-Type，默认纯文本。 */
    private String ackContentType;

    /** 验签失败时的统一结果，不泄漏细节给调用方。 */
    public static CallbackResult invalidSign(String idempotentKey) {
        return CallbackResult.builder()
                .signValid(false)
                .idempotentKey(idempotentKey)
                .event(Event.UNKNOWN)
                .build();
    }

    public enum Event {
        /** 支付成功。 */
        PAID,
        /** 支付失败/拒付。 */
        FAILED,
        /** 无法判定，按不入账处理。 */
        UNKNOWN
    }
}
