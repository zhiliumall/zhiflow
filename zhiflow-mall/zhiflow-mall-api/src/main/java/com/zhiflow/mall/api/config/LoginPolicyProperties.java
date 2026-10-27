package com.zhiflow.mall.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 登录策略配置
 *
 * <p>可通过配置文件调整,修改后需重启才能生效。
 *
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mall.login")
public class LoginPolicyProperties {

    /**
     * 公众号(MP)登录是否强制绑定手机号。
     * 默认 true = 维持现状(无手机号则跳绑手机页);
     * 置 false = 公众号登录不强制绑手机,直接进。
     * 配置项: mall.login.wxmp-force-bind-phone
     */
    private Boolean wxmpForceBindPhone = true;
}
