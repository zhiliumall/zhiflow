package com.zhiflow.mall.api.payment;

import com.zhiflow.mall.api.payment.dto.CallbackResult;
import com.zhiflow.mall.api.payment.dto.PaymentContext;
import com.zhiflow.mall.api.payment.dto.PaymentIntent;
import com.zhiflow.mall.api.payment.dto.PaymentQueryResult;
import com.zhiflow.mall.api.payment.dto.RawCallback;
import com.zhiflow.mall.common.entity.PayOrder;

/**
 * 一条支付渠道。
 *
 * <p><b>这是「支付平台化」的核心接口</b>：新增一条渠道 = 新增一个实现本接口的
 * {@code @Component} + {@code pay_config} 一行 + 前端映射一行，
 * <b>不改任何既有代码</b>。
 *
 * <p>历史背景：本仓此前每加一条渠道都要改 10 个文件、5 处重复的 if/else 分支
 * （{@code ORDER_PAYMENT_TYPE_} 曾在 mall-api 出现 84 次 / 10 个文件），
 * 且前后端各有一处硬编码映射、漏一处不报错。本接口就是为了终结这件事。
 *
 * <h2>实现约定（违反会出资金事故）</h2>
 * <ol>
 *   <li><b>不得依赖订单服务</b>（{@code OrderInfoService} 等）。入账由编排层做，
 *       渠道反向依赖会在 Spring 启动时形成循环引用——本仓有过这类崩溃循环，
 *       且单测直接 {@code new} 所以永远测不到。</li>
 *   <li>{@link #parseCallback} <b>只验签 + 解析，绝不改任何业务状态</b>。</li>
 *   <li>{@link #query} 查不通时返回 {@code UNKNOWN}，<b>不要返回「未支付」</b>，
 *       也不要抛异常——返回「未支付」会让取消流程把已付订单取消并回滚库存。</li>
 *   <li>渠道凭证一律从 {@code pay_config} 读，<b>不硬编码、不写死在配置文件</b>，
 *       密钥要能热更新（部分渠道要求定期轮换）。</li>
 * </ol>
 */
public interface PaymentChannel {

    /**
     * 渠道码，与 {@code pay_config.type} 同值。
     * <p>取值见 {@code PaymentTypeEnum}。注册表按此建索引，重复会在启动时报错。
     */
    String code();

    /**
     * 向渠道发起支付。
     *
     * <p>支付单已由编排层落库，本方法只负责跟渠道打交道并给出前端下一步动作。
     *
     * @param ctx      业务上下文（金额已由服务端算好，绝不含客户端传入的金额）
     * @param payOrder 已落库的支付单，渠道侧一律用 {@code payOrder.payNo} 作 out_trade_no
     */
    PaymentIntent createPayment(PaymentContext ctx, PayOrder payOrder);

    /**
     * 本渠道实际向顾客出示（并回调回来）的币种。
     *
     * <p>默认沿用业务传来的币种。只收固定币种的渠道（如某渠道测试商户只开通了单一外币）必须覆写：
     * 支付单、收银台表单、回调验签三处必须用<b>同一个</b>币种，否则收银台拒单
     * （"幣種參數不正確"）或回调在「币种不符」处被丢——2026-09-11 联调实测两处都踩到。
     *
     * @return 写入 {@code pay_order.curr_code} 的币种；返回 null 表示由支付单服务取缺省值
     */
    default String presentmentCurrency(PaymentContext ctx) {
        return ctx.getCurrCode();
    }

    /**
     * 渠道能力声明。默认值写在实现类，配置项 {@code pay.channel.<code>.cap.*} 可覆盖；
     * 业务侧一律经 {@link ChannelCapabilityResolver} 取，不直接调本方法。
     */
    default ChannelCapabilities capabilities() {
        return ChannelCapabilities.CONSERVATIVE;
    }

    /**
     * 向渠道关单（P6 的可选动作，只在 {@link ChannelCapabilities#close()} 为真时调用）。
     *
     * <p>在事务与锁之外调用。返回 true = 渠道确认已关单；false / 抛异常 = 没关成，支付单保持
     * {@code channel_closed=false}，继续留在补单任务的「已关仍可付」扫描名单里。
     */
    default boolean close(PayOrder payOrder) {
        throw new UnsupportedOperationException("渠道 " + code() + " 未实现关单");
    }

    /**
     * 向渠道查单。用于：取消前复核、回调后防伪二锁、对账兜底、浏览器返回兜底。
     */
    PaymentQueryResult query(PayOrder payOrder);

    /**
     * 验签并解析回调。<b>只解析，不入账。</b>
     */
    CallbackResult parseCallback(RawCallback raw);
}
