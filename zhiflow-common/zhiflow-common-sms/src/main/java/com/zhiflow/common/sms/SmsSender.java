package com.zhiflow.common.sms;

/**
 * 短信发送（可插拔，{@code zhiflow.sms.provider} 选 mock / aliyun / tencent，不配则不启用短信）。
 *
 * <p>只发验证码一种短信：模板在渠道侧预先审核好，模板参数只有验证码本身。
 */
public interface SmsSender {

	/**
	 * 发送验证码短信。
	 *
	 * @param phone 中国大陆手机号（11 位，不带 +86）
	 * @param code  验证码
	 * @throws SmsSendException 渠道明确拒绝或调用失败
	 */
	void sendVerifyCode(String phone, String code);

	/** 渠道标识：mock / aliyun / tencent。 */
	String provider();

	/**
	 * 本渠道下发出的验证码是否固定为 {@code 9999}。只有 dev 环境的 mock 渠道为真。
	 */
	default boolean fixedCode() {
		return false;
	}
}
