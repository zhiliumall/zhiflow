package com.zhiflow.pay.admin.support;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.pay.common.entity.PayConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 支付配置（微信、支付宝）的必填校验。
 *
 * <h2>为什么要有这个类</h2>
 * {@link PayConfig} 上那批 {@code @NotNull} 其实<b>从来没有生效过</b>：
 * {@code PayConfigController} 的 {@code save}/{@code updateById} 入参没有 {@code @Valid}，
 * 类上也没有 {@code @Validated}，整个 pay-admin 模块零处校验注解。
 * 唯一真正在拦的是数据库的 NOT NULL 约束和后台表单的 required —— 前者报错难看、
 * 后者绕过表单就没了。
 *
 * <p>必填项就是数据库 NOT NULL 的那几列（appId、商户密钥、证书路径）。
 */
public final class PayConfigValidator {

    private PayConfigValidator() {
    }

    /**
     * 校验一条支付配置。
     *
     * @return 不通过的原因；全部通过返回 {@code null}
     */
    public static String validate(PayConfig config) {
        if (config == null) {
            return "支付配置不能为空";
        }
        List<String> missing = new ArrayList<>();
        if (StrUtil.isBlank(config.getType())) {
            missing.add("支付类型");
        }
        if (StrUtil.isBlank(config.getModel())) {
            missing.add("模式");
        }
        if (!missing.isEmpty()) {
            return "缺少必填项：" + String.join("、", missing);
        }
        if (StrUtil.isBlank(config.getAppId())) {
            missing.add("appId");
        }
        if (StrUtil.isBlank(config.getMchKey())) {
            missing.add("商户密钥");
        }
        if (StrUtil.isBlank(config.getKeyPath())) {
            missing.add("证书路径");
        }
        return missing.isEmpty() ? null : "缺少必填项：" + String.join("、", missing);
    }
}
