package com.zhiflow.common.sms;

import lombok.extern.slf4j.Slf4j;

/**
 * 模拟短信：不真发，验证码固定为 {@code 9999}。
 *
 * <p>只允许在 {@code spring.profiles.active=dev} 下装配（见 {@code SmsAutoConfiguration}），其它环境配了 mock 直接启动失败——
 * 固定验证码一旦进生产就是一条登录后门。
 */
@Slf4j
public class MockSmsSender implements SmsSender {

	public static final String FIXED_CODE = "9999";

	public MockSmsSender() {
		log.warn("\n==================================================================\n"
				+ "  短信渠道为 mock：不发真实短信，所有验证码固定为 " + FIXED_CODE + "\n"
				+ "  只允许开发环境使用，上线前务必把 zhiflow.sms.provider 改成真实渠道\n"
				+ "==================================================================");
	}

	@Override
	public void sendVerifyCode(String phone, String code) {
		log.warn("[mock 短信] phone={} code={}", phone, code);
	}

	@Override
	public String provider() {
		return "mock";
	}

	@Override
	public boolean fixedCode() {
		return true;
	}
}
