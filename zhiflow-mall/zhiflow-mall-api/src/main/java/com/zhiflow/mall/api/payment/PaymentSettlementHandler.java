package com.zhiflow.mall.api.payment;

import com.zhiflow.mall.common.entity.PayOrder;

/**
 * 业务侧的入账处理器。
 *
 * <p>与 {@link PaymentChannel} 对称：渠道是「网关那一侧」的插件，本接口是「业务那一侧」的插件。
 * 编排层拿到验签通过的回调后，按 {@code bizType} 找到对应处理器把业务状态推进。
 *
 * <h2>为什么要有这层</h2>
 * 如果编排层直接依赖 {@code OrderInfoService}，那么「支付」就与「订单」硬绑死：
 * 会员充值、余额充值、收银台、分销保证金各自的入账又得在编排层里再写一串 if。
 * 更要命的是这会在 Spring 容器里拉出一条
 * {@code 渠道 → 编排 → 订单服务 → …} 的长依赖链，本仓有过这类循环引用把容器炸成
 * 启动崩溃循环的事故，且单测直接 {@code new} 所以永远测不到。
 *
 * <h2>实现约定</h2>
 * <ul>
 *   <li>{@link #onPaid} <b>必须幂等</b>。编排层已用支付单状态与回调唯一索引挡了两道，
 *       但渠道重推、对账补单、人工重放仍可能让它被调用多次。</li>
 *   <li>不要在这里再去问渠道要状态——编排层已经查过一次了。</li>
 * </ul>
 */
public interface PaymentSettlementHandler {

    /** 业务类型，与 {@code pay_order.biz_type} 同值。 */
    String bizType();

    /**
     * 支付成功入账。此时 {@code pay_order} 已经是 PAID，钱确实到了。
     *
     * <p>抛异常会让整个回调处理失败，渠道随后重推——对可恢复的故障这是对的；
     * 对「业务数据本身有问题」这类不可恢复的情况，请自行落错误并正常返回，
     * 否则渠道会一直重推到重试窗口耗尽。
     */
    void onPaid(PayOrder payOrder);
}
