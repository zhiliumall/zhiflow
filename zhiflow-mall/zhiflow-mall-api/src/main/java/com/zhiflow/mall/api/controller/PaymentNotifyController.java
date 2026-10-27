/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.util.ClientIpResolver;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.api.payment.PaymentService;
import com.zhiflow.mall.api.payment.dto.RawCallback;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 支付回调统一入口。
 *
 * <p>取代「一个渠道一个 notify 端点」的老形态（mall-api 曾有 14 个 {@code notify-*}）。
 * 验签、四项核对、幂等、留痕、入账全部收敛到 {@link PaymentService#handleCallback}，
 * 各渠道只提供验签与解析。
 *
 * <h2>路径为什么长这样</h2>
 * 挂在**既有的一级前缀**下（{@code /orderinfo} 同级，走 {@code /mallapi/**} 那条网关路由），
 * <b>不新开一级路径</b>。本仓有过新 Controller 用了未配路由的新前缀、
 * 整个模块在生产静默 404 且长期无人发现的事故。
 *
 * <p>⚠️ 即便挂在既有前缀下，<b>发版后仍必须从外网实打一次</b>，
 * 并拿一个本该成功的老接口做对照样本比响应体——只看状态码会被 SPA fallback 的假 200 骗过去。
 *
 * <h2>鉴权模型</h2>
 * 本端点必须匿名可达（渠道服务器来调，没有登录态），需进网关 {@code release-urls} 白名单。
 * <b>验签是唯一的门。</b>
 *
 * @date 2026-09-07
 */
@Slf4j
@RestController
@RequestMapping("/paynotify")
@Tag(name = "支付回调统一入口")
public class PaymentNotifyController {

    /**
     * 租户 id 合法性校验。
     *
     * <p>非法的租户值会让 {@code SET search_path} 静默落到默认 schema，
     * 于是一笔回调可能被写进错误的租户库。这里先卡死格式，再交给下游。
     */
    private static final Pattern TENANT_ID = Pattern.compile("^[0-9]{1,20}$");

    /** 渠道码同样来自公网可填的 path，限制字符集避免被拿去做注入尝试。 */
    private static final Pattern CHANNEL_CODE = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private final PaymentService paymentService;

    public PaymentNotifyController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 接收渠道回调。
     *
     * <p>响应体由渠道实现决定（支付宝要纯文本 {@code success}，微信要特定 XML 报文）——
     * 回错会让渠道判定投递失败并按其策略反复重推。
     */
    @Operation(summary = "支付回调（统一入口）")
    @PostMapping(value = "/{channel}/{tenantId}",
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> notify(@PathVariable("channel") String channel,
                                         @PathVariable("tenantId") String tenantId,
                                         @RequestBody(required = false) String body,
                                         HttpServletRequest request) {
        if (!CHANNEL_CODE.matcher(channel).matches() || !TENANT_ID.matcher(tenantId).matches()) {
            // 不回显入参，避免把校验规则喂给探测者
            log.error("支付回调入参非法已丢弃：channel/tenantId 格式不合规，sourceIp={}",
                    ClientIpResolver.resolve(request, request::getRemoteAddr));
            return ResponseEntity.badRequest().body("FAIL");
        }

        TenantContextHolder.setTenantId(tenantId);
        try {
            RawCallback raw = RawCallback.builder()
                    .channelCode(channel)
                    .tenantId(tenantId)
                    .body(body)
                    .params(readParams(request))
                    .headers(readHeaders(request))
                    .sourceIp(ClientIpResolver.resolve(request, request::getRemoteAddr))
                    .build();

            PaymentService.CallbackOutcome outcome = paymentService.handleCallback(raw);
            return ResponseEntity.ok()
                    .header("Content-Type", outcome.contentType())
                    .body(outcome.ackBody());
        }
        catch (Exception e) {
            // 处理失败时**不要**回渠道期望的成功应答——要吃满对方的自愈重试窗口。
            log.error("支付回调处理异常：channel={} tenantId={}", channel, tenantId, e);
            return ResponseEntity.ok().body("FAIL");
        }
        finally {
            TenantContextHolder.clear();
        }
    }

    private Map<String, String> readParams(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (v != null && v.length > 0) {
                params.put(k, v[0]);
            }
        });
        return Collections.unmodifiableMap(params);
    }

    private Map<String, String> readHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name.toLowerCase(), request.getHeader(name));
        }
        return Collections.unmodifiableMap(headers);
    }
}
