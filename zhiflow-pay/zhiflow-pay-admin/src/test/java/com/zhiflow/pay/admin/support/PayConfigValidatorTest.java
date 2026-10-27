package com.zhiflow.pay.admin.support;

import com.zhiflow.pay.common.entity.PayConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付配置（微信、支付宝）的必填校验：库里 app_id / key_path 不是 NOT NULL，后端这道是唯一的拦。
 */
class PayConfigValidatorTest {

    private PayConfig complete(String type) {
        PayConfig c = new PayConfig();
        c.setType(type);
        c.setModel("2");
        c.setAppId("wx1234567890");
        c.setMchKey("secret");
        c.setKeyPath("/cert/1/apiclient_cert.p12");
        return c;
    }

    @Test
    void configWithAllRequiredFieldsPasses() {
        assertNull(PayConfigValidator.validate(complete("1")));
        assertNull(PayConfigValidator.validate(complete("2")));
    }

    @Test
    void missingAppIdIsRejected() {
        PayConfig c = complete("1");
        c.setAppId(" ");
        String err = PayConfigValidator.validate(c);
        assertNotNull(err, "缺 appId 必须被拦");
        assertTrue(err.contains("appId"), err);
    }

    @Test
    void missingMchKeyIsRejected() {
        PayConfig c = complete("2");
        c.setMchKey(null);
        assertNotNull(PayConfigValidator.validate(c));
    }

    @Test
    void missingKeyPathIsRejected() {
        PayConfig c = complete("1");
        c.setKeyPath(null);
        String err = PayConfigValidator.validate(c);
        assertNotNull(err);
        assertTrue(err.contains("证书路径"), err);
    }

    @Test
    void typeAndModelAreAlwaysRequired() {
        PayConfig c = new PayConfig();
        assertNotNull(PayConfigValidator.validate(c));

        c.setType("1");
        String err = PayConfigValidator.validate(c);
        assertNotNull(err);
        assertTrue(err.contains("模式"), err);
    }

    @Test
    void nullConfigIsRejected() {
        assertNotNull(PayConfigValidator.validate(null));
    }
}
