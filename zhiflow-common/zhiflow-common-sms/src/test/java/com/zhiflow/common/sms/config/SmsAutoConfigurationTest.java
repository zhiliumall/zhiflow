package com.zhiflow.common.sms.config;

import com.zhiflow.common.sms.AliyunSmsSender;
import com.zhiflow.common.sms.MockSmsSender;
import com.zhiflow.common.sms.SmsSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 短信渠道装配：不配不启用；mock（固定验证码 9999）只许 dev profile；真实渠道缺签名 / 模板直接起不来。
 */
class SmsAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SmsAutoConfiguration.class));

	@Test
	@DisplayName("没配 provider：没有 SmsSender，短信功能整体不启用")
	void disabledByDefault() {
		runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(SmsSender.class));
	}

	@Test
	@DisplayName("mock + dev：装配 MockSmsSender，验证码固定")
	void mockInDev() {
		runner.withPropertyValues("zhiflow.sms.provider=mock", "spring.profiles.active=dev")
				.run(ctx -> {
					assertThat(ctx).hasSingleBean(MockSmsSender.class);
					assertThat(ctx.getBean(SmsSender.class).fixedCode()).isTrue();
				});
	}

	@Test
	@DisplayName("mock 但不在 dev（如单体默认的 all）：启动失败，9999 进不了别的环境")
	void mockOutsideDevFailsFast() {
		runner.withPropertyValues("zhiflow.sms.provider=mock", "spring.profiles.active=all")
				.run(ctx -> assertThat(ctx).hasFailed()
						.getFailure().rootCause().hasMessageContaining("只允许在 spring.profiles.active=dev"));
		runner.withPropertyValues("zhiflow.sms.provider=mock")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	@DisplayName("真实渠道缺签名或模板：启动失败")
	void realProviderRequiresTemplate() {
		runner.withPropertyValues("zhiflow.sms.provider=aliyun", "zhiflow.sms.sign-name=智流")
				.run(ctx -> assertThat(ctx).hasFailed()
						.getFailure().rootCause().hasMessageContaining("zhiflow.sms.template-code"));
	}

	@Test
	@DisplayName("阿里云配齐：装配真实渠道，验证码不固定")
	void aliyunConfigured() {
		runner.withPropertyValues("zhiflow.sms.provider=aliyun", "zhiflow.sms.sign-name=智流",
						"zhiflow.sms.template-code=SMS_1", "zhiflow.sms.aliyun.access-key-id=ak",
						"zhiflow.sms.aliyun.access-key-secret=sk")
				.run(ctx -> {
					assertThat(ctx).hasSingleBean(AliyunSmsSender.class);
					assertThat(ctx.getBean(SmsSender.class).fixedCode()).isFalse();
				});
	}

	@Test
	@DisplayName("不认识的渠道名：启动失败并列出可选值")
	void unknownProvider() {
		runner.withPropertyValues("zhiflow.sms.provider=twilio")
				.run(ctx -> assertThat(ctx).hasFailed()
						.getFailure().rootCause().hasMessageContaining("mock / aliyun / tencent"));
	}
}
