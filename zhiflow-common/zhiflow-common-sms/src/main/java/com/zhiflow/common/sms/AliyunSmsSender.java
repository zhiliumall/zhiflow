package com.zhiflow.common.sms;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aliyuncs.CommonRequest;
import com.aliyuncs.CommonResponse;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;
import com.zhiflow.common.sms.config.SmsProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * 阿里云短信（dysmsapi SendSms，模板参数 {@code {"code":"..."}}）。
 */
@Slf4j
public class AliyunSmsSender implements SmsSender {

	private final SmsProperties properties;

	private final IAcsClient client;

	public AliyunSmsSender(SmsProperties properties) {
		this.properties = properties;
		SmsProperties.Aliyun aliyun = properties.getAliyun();
		this.client = new DefaultAcsClient(DefaultProfile.getProfile(aliyun.getRegionId(), aliyun.getAccessKeyId(),
				aliyun.getAccessKeySecret()));
	}

	@Override
	public void sendVerifyCode(String phone, String code) {
		CommonRequest request = new CommonRequest();
		request.setSysMethod(MethodType.POST);
		request.setSysDomain("dysmsapi.aliyuncs.com");
		request.setSysVersion("2017-05-25");
		request.setSysAction("SendSms");
		request.putQueryParameter("RegionId", properties.getAliyun().getRegionId());
		request.putQueryParameter("PhoneNumbers", phone);
		request.putQueryParameter("SignName", properties.getSignName());
		request.putQueryParameter("TemplateCode", properties.getTemplateCode());
		request.putQueryParameter("TemplateParam", JSONUtil.toJsonStr(java.util.Map.of("code", code)));
		CommonResponse response;
		try {
			response = client.getCommonResponse(request);
		}
		catch (Exception e) {
			throw new SmsSendException("阿里云短信调用失败", e);
		}
		JSONObject body = JSONUtil.parseObj(response.getData());
		if (!"OK".equals(body.getStr("Code"))) {
			log.error("阿里云短信发送失败 phone={} code={} message={}", phone, body.getStr("Code"), body.getStr("Message"));
			throw new SmsSendException("短信发送失败：" + body.getStr("Message"));
		}
	}

	@Override
	public String provider() {
		return "aliyun";
	}
}
