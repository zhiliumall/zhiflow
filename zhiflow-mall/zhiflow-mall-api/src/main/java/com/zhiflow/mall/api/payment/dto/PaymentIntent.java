package com.zhiflow.mall.api.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 发起支付的统一出参（渠道 → 编排层 → 前端）。
 *
 * <p>形状刻意对齐 H5 现有的 {@code next_action} 协议，好让前端**不必新写跳转逻辑**：
 * H5 前端的支付工具函数里已经有 {@code next_action.data}（拉起 JSAPI）
 * 与 {@code next_action.type === 'redirect'}（{@code window.location.href}）两个分支。
 *
 * <p>新渠道只要落在这几种形态里，前端零改动即可跑通。
 */
@Data
@Builder
public class PaymentIntent {

    /** 支付单号，前端轮询支付结果时回传。 */
    private String payNo;

    private String channelCode;

    /** 下一步动作的形态。 */
    private Type type;

    /**
     * 需要跳转时的目标 URL（{@link Type#REDIRECT} / {@link Type#FORM_POST}）。
     * <p>FORM_POST 场景下这里给的是**我方中转页**地址——渠道要求浏览器 form POST 过去，
     * 中转页负责吐一个自动提交的表单，前端仍然只是 {@code location.href}。
     */
    private String redirectUrl;

    /** 需要客户端 SDK 拉起时的参数（微信 JSAPI 的 timeStamp/nonceStr/paySign 等）。 */
    private Map<String, Object> payParams;

    /** 已完成，无需前端再做动作（余额支付、0 元单）。 */
    public boolean isDone() {
        return type == Type.DONE;
    }

    public enum Type {
        /** 调起客户端支付 SDK。 */
        SDK,
        /** 浏览器跳转到渠道收银台。 */
        REDIRECT,
        /** 需经我方中转页 form POST 到渠道（部分托管收银台类渠道即此形态）。 */
        FORM_POST,
        /** 支付已完成，前端直接看结果。 */
        DONE
    }
}
