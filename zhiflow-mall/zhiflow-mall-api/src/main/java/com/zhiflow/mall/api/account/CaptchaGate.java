package com.zhiflow.mall.api.account;

import cloud.tianai.captcha.spring.plugins.secondary.SecondaryVerificationApplication;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 图形（滑块）验证码二次校验：前端先过 {@code /captcha/check} 拿到一次性 token，提交表单时带上，这里校验即作废。
 */
@Slf4j
@Component
public class CaptchaGate {

	private final ObjectProvider<SecondaryVerificationApplication> secondaryVerification;

	public CaptchaGate(ObjectProvider<SecondaryVerificationApplication> secondaryVerification) {
		this.secondaryVerification = secondaryVerification;
	}

	/**
	 * @param token 前端过滑块后拿到的 token
	 * @return true = 通过。没有装配二次校验组件时一律不通过（fail-closed），并打 ERROR 日志
	 */
	public boolean pass(String token) {
		SecondaryVerificationApplication app = secondaryVerification.getIfAvailable();
		if (app == null) {
			log.error("CAPTCHA_MISSING 未装配滑块验证码二次校验（captcha.secondary.enabled），需要图形验证码的操作一律拒绝");
			return false;
		}
		return StrUtil.isNotBlank(token) && app.secondaryVerification(token);
	}
}
