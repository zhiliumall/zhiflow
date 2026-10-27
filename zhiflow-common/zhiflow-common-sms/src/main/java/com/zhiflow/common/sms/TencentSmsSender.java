package com.zhiflow.common.sms;

import com.zhiflow.common.sms.config.SmsProperties;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.sms.v20210111.SmsClient;
import com.tencentcloudapi.sms.v20210111.models.SendSmsRequest;
import com.tencentcloudapi.sms.v20210111.models.SendSmsResponse;
import com.tencentcloudapi.sms.v20210111.models.SendStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * 腾讯云短信（SendSms 2021-01-11，模板唯一参数是验证码）。
 */
@Slf4j
public class TencentSmsSender implements SmsSender {

	private final SmsProperties properties;

	private final SmsClient client;

	public TencentSmsSender(SmsProperties properties) {
		this.properties = properties;
		SmsProperties.Tencent tencent = properties.getTencent();
		this.client = new SmsClient(new Credential(tencent.getSecretId(), tencent.getSecretKey()), tencent.getRegion());
	}

	@Override
	public void sendVerifyCode(String phone, String code) {
		SendSmsRequest request = new SendSmsRequest();
		request.setSmsSdkAppId(properties.getTencent().getSdkAppId());
		request.setSignName(properties.getSignName());
		request.setTemplateId(properties.getTemplateCode());
		request.setTemplateParamSet(new String[]{code});
		request.setPhoneNumberSet(new String[]{"+86" + phone});
		SendSmsResponse response;
		try {
			response = client.SendSms(request);
		}
		catch (Exception e) {
			throw new SmsSendException("腾讯云短信调用失败", e);
		}
		SendStatus[] statuses = response.getSendStatusSet();
		SendStatus status = statuses == null || statuses.length == 0 ? null : statuses[0];
		if (status == null || !"Ok".equalsIgnoreCase(status.getCode())) {
			log.error("腾讯云短信发送失败 phone={} code={} message={}", phone, status == null ? null : status.getCode(),
					status == null ? null : status.getMessage());
			throw new SmsSendException("短信发送失败：" + (status == null ? "无响应" : status.getMessage()));
		}
	}

	@Override
	public String provider() {
		return "tencent";
	}
}
