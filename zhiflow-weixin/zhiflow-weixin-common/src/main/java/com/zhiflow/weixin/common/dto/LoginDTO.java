package com.zhiflow.weixin.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class LoginDTO {
	private String appId;
	private String jsCode;
	private String scope;

	private String unionId;

	private String openId;
	@Schema(description = "所在城市")
	private String city;
	@Schema(description = "所在省份")
	private String province;
	@Schema(description = "地址")
	private String address;

	@Schema(description = "邀请用户id")
	private String inviteUserId;

	@Schema(description = "商城用户ID（用于绑定openId）")
	private String mallUserId;
}
