package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户简要信息 VO
 * 用于客服系统批量查询用户信息（也用于跨服务 HTTP Interface 调用）。
 */
@Data
@Schema(description = "用户简要信息")
public class UserInfoSimpleVO {

    @Schema(description = "用户ID")
    private String id;

    @Schema(description = "昵称")
    private String nickName;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "头像")
    private String headimgUrl;
}
