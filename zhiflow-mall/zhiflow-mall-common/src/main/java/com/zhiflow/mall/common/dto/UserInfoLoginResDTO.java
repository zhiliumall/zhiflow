package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "User login response")
public class UserInfoLoginResDTO {
    @Schema(description = "User ID")
    private String id;
    @Schema(description = "User code")
    private Integer userCode;
    @Schema(description = "Nickname")
    private String nickName;
    @Schema(description = "Avatar URL")
    private String headimgUrl;
    @Schema(description = "App type")
    private String appType;
    @Schema(description = "App ID")
    private String appId;
    @Schema(description = "Is new user")
    private boolean newUser;
    private String thirdSession;

}
