package com.zhiflow.mall.api.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 渠道回调的原始输入。
 *
 * <p>统一回调入口把 HTTP 层的东西原样装进来交给渠道实现去验签，
 * 渠道实现**不接触 HttpServletRequest**——这样它可以被单测直接喂夹具。
 */
@Data
@Builder
public class RawCallback {

    /** 渠道码，来自回调 URL 的 path 变量。 */
    private String channelCode;

    /** 租户 id，来自回调 URL 的 path 变量。**必须先校验合法**再用于切 schema。 */
    private String tenantId;

    /** 原始请求体（XML/JSON/表单原文）。 */
    private String body;

    /** 表单或 query 参数。 */
    private Map<String, String> params;

    /** 请求头（部分渠道把签名放头里）。 */
    private Map<String, String> headers;

    /** 来源 IP，可用于在验签之外加一层 IP 白名单做纵深防御。 */
    private String sourceIp;
}
