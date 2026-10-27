package com.zhiflow.mall.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Payment check result
 * Used to determine if an order has been paid before cancellation
 *
 * <p><b>三态语义</b>：{@code paid} / {@code notPaid} / {@code unknown}。
 * 早期只有前两态，导致「查不到支付网关」被迫在「当作未支付（可能取消掉已付款订单 = 丢账）」
 * 与「抛异常（打断整条取消流程 = 库存锁死）」之间二选一，两个都是错的。
 * 第三态把「我方无法判定」独立出来：调用方既不入账，也不据此取消，留待下一轮重试。
 *
 * <p>判定规则（调用方必须遵守）：
 * <ul>
 *   <li>{@link #isPaid()} —— 网关**明确**答复已支付。阻止取消并入账。</li>
 *   <li>{@link #isUnknown()} —— 网关不可达 / 响应无法解析。**本轮不取消**，ERROR 留痕后重试。</li>
 *   <li>两者皆 false —— 明确未支付（含「网关侧查无此单」）。放行取消。</li>
 * </ul>
 *
 * <p>⚠️「查无此单」必须归入**未支付**而非 unknown：渠道侧本就可能因为用户从未真正跳转支付
 * 而没有这笔意图，若归为 unknown 则该订单永远取消不掉、库存被永久占用。
 * 反过来也绝不能因为「查无此单」就推断成「已关闭/已支付」——参考 yudao
 * {@code PayOrderServiceImpl#syncOrder} 的注释：拉起支付后用户稍后才付款的场景真实存在，
 * 一律以回调为准。
 */
@Data
@AllArgsConstructor
public class PaymentCheckResult {
    /**
     * Whether the order has been paid
     */
    private boolean paid;

    /**
     * 是否「无法判定」。true 时 {@link #paid} 必为 false，但不代表未支付。
     */
    private boolean unknown;

    /**
     * Payment type: 1-WeChat, 2-Alipay
     * From MallConstants.ORDER_PAYMENT_TYPE_1 and ORDER_PAYMENT_TYPE_2
     */
    private String paymentType;

    /**
     * Transaction ID from payment gateway
     */
    private String transactionId;

    /**
     * Payment time
     */
    private java.time.LocalDateTime paymentTime;

    /**
     * Trade type from WeChat payment
     */
    private String tradeType;

    /**
     * 支付状态机 v1.2：查到已付的是新框架的哪张支付单（老渠道为 null）。调用方据此先把支付单确认为已付（P4 / P7），
     * 再推进订单——否则订单已付、支付单却停在 PAYING，对账与退款额度都对不上。
     */
    private String payNo;

    /**
     * Static factory method for "not paid" result
     */
    public static PaymentCheckResult notPaid() {
        return new PaymentCheckResult(false, false, null, null, null, null, null);
    }

    /**
     * 「无法判定」——网关不可达、响应非法、凭证缺失等。调用方不得据此取消订单。
     */
    public static PaymentCheckResult unknown() {
        return new PaymentCheckResult(false, true, null, null, null, null, null);
    }

    /**
     * Static factory method for "paid" result (3-arg version)
     */
    public static PaymentCheckResult paid(String paymentType, String transactionId, java.time.LocalDateTime paymentTime) {
        return new PaymentCheckResult(true, false, paymentType, transactionId, paymentTime, null, null);
    }

    /**
     * Static factory method for "paid" result (4-arg version with tradeType)
     */
    public static PaymentCheckResult paid(String paymentType, String transactionId, java.time.LocalDateTime paymentTime, String tradeType) {
        return new PaymentCheckResult(true, false, paymentType, transactionId, paymentTime, tradeType, null);
    }

    /** 新框架支付单查到已付（带支付单号，调用方先确认支付单再推进订单）。 */
    public static PaymentCheckResult paidOnPayOrder(String paymentType, String payNo, String transactionId,
            java.time.LocalDateTime paymentTime, String tradeType) {
        return new PaymentCheckResult(true, false, paymentType, transactionId, paymentTime, tradeType, payNo);
    }
}
