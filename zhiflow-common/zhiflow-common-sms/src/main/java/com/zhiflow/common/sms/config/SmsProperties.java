package com.zhiflow.common.sms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 短信配置（{@code zhiflow.sms.*}）。{@code provider} 不配 = 不启用短信：手机号验证码登录、短信注册、换绑手机号都不可用，
 * 「用户名/手机号 + 密码 + 图形验证码」注册与登录照常可用。
 */
@Data
@ConfigurationProperties(prefix = "zhiflow.sms")
public class SmsProperties {

	/** mock / aliyun / tencent；空 = 不启用 */
	private String provider;

	/** 短信签名 */
	private String signName;

	/** 验证码模板：阿里云填模板 CODE（参数名 code），腾讯云填模板 ID（唯一参数是验证码） */
	private String templateCode;

	private Aliyun aliyun = new Aliyun();

	private Tencent tencent = new Tencent();

	@Data
	public static class Aliyun {

		private String regionId = "cn-hangzhou";

		private String accessKeyId;

		private String accessKeySecret;
	}

	@Data
	public static class Tencent {

		private String region = "ap-guangzhou";

		private String secretId;

		private String secretKey;

		/** 短信应用 SdkAppId */
		private String sdkAppId;
	}
}
