package com.zhiflow.mall.common.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户完整信息投影（脱敏版本，跨服务安全）。
 * 屏蔽 UserInfo 实体里的 password / idCard / thirdSession 等敏感字段。
 */
@Data
@Schema(description = "用户完整信息（投影）")
public class UserInfoFullVO {

    @Schema(description = "用户ID")
    private String id;

    @Schema(description = "昵称")
    private String nickName;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "头像")
    private String headimgUrl;

    @Schema(description = "性别 0/1/2")
    private String sex;

    @Schema(description = "国家")
    private String country;

    @Schema(description = "省")
    private String province;

    @Schema(description = "市")
    private String city;

    @Schema(description = "详细地址")
    private String address;

    @Schema(description = "注册渠道 (h5/wechat/mp/...)")
    private String appType;

    @Schema(description = "注册时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "用户简码")
    private Integer userCode;

    @Schema(description = "余额")
    private BigDecimal balance;

    @Schema(description = "积分")
    private Integer points;

    @Schema(description = "设备信息（JSON/纯文本）")
    private String deviceInfo;

    @Schema(description = "设备类型 h5/wechat/mp/android/ios")
    private String deviceType;

    @Schema(description = "最近登录时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastLoginTime;

    @Schema(description = "最近登录 IP")
    private String lastLoginIp;

    @Schema(description = "注册 IP")
    private String registerIp;

    @Schema(description = "优惠券数量")
    private Integer couponNum;
}
