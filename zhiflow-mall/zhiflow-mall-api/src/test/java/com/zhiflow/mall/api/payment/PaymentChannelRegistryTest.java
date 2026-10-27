package com.zhiflow.mall.api.payment;

import com.zhiflow.mall.api.payment.dto.CallbackResult;
import com.zhiflow.mall.api.payment.dto.PaymentContext;
import com.zhiflow.mall.api.payment.dto.PaymentIntent;
import com.zhiflow.mall.api.payment.dto.PaymentQueryResult;
import com.zhiflow.mall.api.payment.dto.RawCallback;
import com.zhiflow.mall.common.entity.PayOrder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付渠道注册表（「工厂」本体）的行为。
 *
 * <p>刻意<b>不用 Mockito</b>：本仓在 JDK25 下 mock 具体类会污染 Byte Buddy 的 TypeCache，
 * 害同批次后续测试 mock 接口失败（单独跑过、全量跑挂就是它）。这里手写测试替身，零框架依赖。
 */
class PaymentChannelRegistryTest {

    /** 最小可用的渠道替身，只认自己的码。 */
    private static class StubChannel implements PaymentChannel {

        private final String code;

        StubChannel(String code) {
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public PaymentIntent createPayment(PaymentContext ctx, PayOrder payOrder) {
            return PaymentIntent.builder().payNo(payOrder.getPayNo()).build();
        }

        @Override
        public PaymentQueryResult query(PayOrder payOrder) {
            return PaymentQueryResult.notPaid();
        }

        @Override
        public CallbackResult parseCallback(RawCallback raw) {
            return CallbackResult.invalidSign("stub");
        }
    }

    @Test
    void requireReturnsTheChannelWithMatchingCode() {
        StubChannel wechat = new StubChannel("1");
        StubChannel alipay = new StubChannel("2");
        PaymentChannelRegistry registry = new PaymentChannelRegistry(List.of(wechat, alipay));

        assertSame(wechat, registry.require("1"));
        assertSame(alipay, registry.require("2"));
        assertTrue(registry.supports("2"));
        assertEquals(2, registry.registeredCodes().size());
    }

    /**
     * 找不到渠道必须<b>抛异常，不能返回 null</b>。
     *
     * <p>静默返回 null 会让调用方走进空指针，或更糟——被上层当成「查不到即未支付」，
     * 从而把一笔其实已付的订单取消掉并回滚库存。宁可当场失败。
     */
    @Test
    void requireThrowsOnUnknownCodeInsteadOfReturningNull() {
        PaymentChannelRegistry registry = new PaymentChannelRegistry(List.of(new StubChannel("4")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.require("99"));
        assertTrue(e.getMessage().contains("99"), "异常信息要带上出问题的渠道码：" + e.getMessage());
        assertFalse(registry.supports("99"));
        assertTrue(registry.find("99").isEmpty());
    }

    /**
     * 两条渠道抢同一个码必须在<b>启动时</b>炸掉。
     *
     * <p>否则谁生效取决于 Spring 的注入顺序——同一份回调可能今天被 A 渠道验签、
     * 明天被 B 渠道验签，是笔资金事故的温床。
     */
    @Test
    void duplicateChannelCodeFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PaymentChannelRegistry(List.of(new StubChannel("2"), new StubChannel("2"))));
        assertTrue(e.getMessage().contains("冲突"), "要说清是渠道码冲突：" + e.getMessage());
    }

    @Test
    void blankChannelCodeFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new PaymentChannelRegistry(List.of(new StubChannel(" "))));
    }

    /** 没有任何渠道时也要能起来——抽象层先上线、渠道后接入是本次的既定顺序。 */
    @Test
    void emptyRegistryIsAllowed() {
        PaymentChannelRegistry registry = new PaymentChannelRegistry(List.of());
        assertTrue(registry.registeredCodes().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.require("4"));
    }
}
