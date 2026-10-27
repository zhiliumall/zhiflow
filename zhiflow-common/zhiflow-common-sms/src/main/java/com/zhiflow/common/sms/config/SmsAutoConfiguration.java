package com.zhiflow.common.sms.config;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.sms.AliyunSmsSender;
import com.zhiflow.common.sms.MockSmsSender;
import com.zhiflow.common.sms.SmsSender;
import com.zhiflow.common.sms.TencentSmsSender;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * 短信自动配置：按 {@code zhiflow.sms.provider} 装配一个 {@link SmsSender}；不配则没有这个 Bean（短信不启用）。
 */
@AutoConfiguration
@EnableConfigurationProperties(SmsProperties.class)
public class SmsAutoConfiguration {

	@Bean
	@ConditionalOnExpression("'${zhiflow.sms.provider:}' != ''")
	public SmsSender smsSender(SmsProperties properties, Environment environment) {
		String provider = StrUtil.trim(properties.getProvider());
		return switch (provider) {
			case "mock" -> {
				// 固定验证码只给开发环境：其它环境配了 mock 直接启动失败
				if (!Arrays.asList(environment.getActiveProfiles()).contains("dev")) {
					throw new IllegalStateException("zhiflow.sms.provider=mock 只允许在 spring.profiles.active=dev 下使用");
				}
				yield new MockSmsSender();
			}
			case "aliyun" -> new AliyunSmsSender(requireTemplate(properties));
			case "tencent" -> new TencentSmsSender(requireTemplate(properties));
			default -> throw new IllegalStateException("不支持的短信渠道 zhiflow.sms.provider=" + provider
					+ "，可选 mock / aliyun / tencent");
		};
	}

	private static SmsProperties requireTemplate(SmsProperties properties) {
		if (StrUtil.isBlank(properties.getSignName()) || StrUtil.isBlank(properties.getTemplateCode())) {
			throw new IllegalStateException("启用真实短信渠道必须配置 zhiflow.sms.sign-name 与 zhiflow.sms.template-code");
		}
		return properties;
	}
}
