package com.zhiflow.weixin.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 微信开发数据
 */
@Data
public class WxOpenDataDTO {
	private String appId;
	private String userId;
	private String encryptedData;
	private String errMsg;
	private String iv;
	private String rawData;
	private String signature;
	private String sessionKey;
	private String mallUserId;
	private String jsCode;
	private String inviteUserId;

	private String openId;
	@Schema(description = "所在城市")
	private String city;
	@Schema(description = "所在省份")
	private String province;
	@Schema(description = "地址")
	private String address;
}
