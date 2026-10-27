package com.zhiflow.mall.api.payment;

import com.zhiflow.mall.common.orderno.OrderNoGenerator;
import org.springframework.stereotype.Component;

/**
 * 支付单号生成器。
 *
 * <p><b>刻意包一层而不是直接 new 一个新的 Snowflake。</b>
 * {@link OrderNoGenerator} 的类注释里写得很清楚：同一 JVM 内若出现第二个各自从
 * {@code sequence=0} 起步的 Snowflake 实例，<b>同毫秒可产出完全相同的号</b>。
 * 复用注入进来的那个 bean 就是复用同一个 Hutool Singleton 实例，序列连续，
 * 不引入任何新的碰撞源。
 *
 * <p>前缀 {@code P} 只为人眼可分辨「这是支付单号不是订单号」，
 * 并不承担唯一性——唯一性完全来自雪花号本身与 {@code pay_order.pay_no} 上的唯一索引。
 *
 * <p>长度：{@code P} + 19 位 = 20 字符。各渠道 {@code out_trade_no} 的长度上限普遍在
 * 32 以上（微信、支付宝均可容纳），
 * <b>新增渠道时仍需核对该渠道的长度与字符集限制</b>。
 */
@Component
public class PayNoGenerator {

    /** 支付单号前缀，仅作可读性标识。 */
    static final String PREFIX = "P";

    private final OrderNoGenerator orderNoGenerator;

    public PayNoGenerator(OrderNoGenerator orderNoGenerator) {
        this.orderNoGenerator = orderNoGenerator;
    }

    /**
     * 生成一个支付单号。
     *
     * <p>时钟回拨超出容忍区时底层会抛 {@code BizException("系统繁忙，请稍后重试")}，
     * 这里不额外包装——发不出号就不该继续发起支付。
     */
    public String next() {
        return PREFIX + orderNoGenerator.next();
    }
}
