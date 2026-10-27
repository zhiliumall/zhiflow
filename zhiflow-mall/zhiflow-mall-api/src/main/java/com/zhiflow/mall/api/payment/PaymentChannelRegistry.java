package com.zhiflow.mall.api.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 支付渠道注册表 —— 「工厂」本体。
 *
 * <p>Spring 把所有 {@link PaymentChannel} 实现注入进来，按 {@link PaymentChannel#code()}
 * 建索引。<b>新增渠道零改动本类</b>。
 *
 * <p><b>为什么用构造器注入 {@code List}</b>：本仓有过「新增 {@code @Component} 急切注入
 * 把既有循环引用炸成启动崩溃循环」的事故，而且单测直接 {@code new} 所以永远测不到。
 * 构造器注入一个 List 不引入对具体业务服务的依赖，是这里最安全的形态。
 * 各渠道实现同样不得反向依赖订单服务（见 {@link PaymentChannel} 类注释）。
 */
@Slf4j
@Component
public class PaymentChannelRegistry {

    private final Map<String, PaymentChannel> byCode;

    public PaymentChannelRegistry(List<PaymentChannel> channels) {
        Map<String, PaymentChannel> map = new LinkedHashMap<>();
        for (PaymentChannel channel : channels) {
            String code = channel.code();
            if (code == null || code.isBlank()) {
                throw new IllegalStateException(
                        "支付渠道 " + channel.getClass().getName() + " 的 code() 为空");
            }
            PaymentChannel previous = map.put(code, channel);
            if (previous != null) {
                // 两条渠道抢同一个码 ⇒ 谁生效取决于注入顺序，是笔资金事故的温床。
                // 启动时直接炸掉，不允许带着这种状态跑起来。
                throw new IllegalStateException(String.format(
                        "支付渠道码冲突：%s 与 %s 都声明了 code=%s",
                        previous.getClass().getName(), channel.getClass().getName(), code));
            }
        }
        this.byCode = Collections.unmodifiableMap(map);
        log.info("支付渠道注册完成，共 {} 条：{}", map.size(), map.keySet());
    }

    /**
     * 取渠道实现；找不到直接抛。
     *
     * <p><b>刻意不返回 null。</b>「渠道码没人认领」意味着配置与代码不同步，
     * 静默返回 null 会让调用方走进空指针或更糟的「当作未支付」——
     * 后者会把已付订单取消掉。宁可当场失败。
     */
    public PaymentChannel require(String code) {
        PaymentChannel channel = byCode.get(code);
        if (channel == null) {
            throw new IllegalArgumentException(
                    "未知支付渠道码：" + code + "，已注册：" + byCode.keySet());
        }
        return channel;
    }

    /** 探测用；调用方必须自己处理空。入账链路上请用 {@link #require(String)}。 */
    public Optional<PaymentChannel> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    public boolean supports(String code) {
        return byCode.containsKey(code);
    }

    /** 已注册的渠道码，只读。 */
    public java.util.Set<String> registeredCodes() {
        return byCode.keySet();
    }
}
