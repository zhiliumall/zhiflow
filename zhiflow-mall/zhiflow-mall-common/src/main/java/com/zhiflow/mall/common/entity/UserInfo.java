/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.zhiflow.common.core.entity.base.TenantEntity;
import com.baomidou.mybatisplus.annotation.*;
import com.zhiflow.common.core.sensitive.Sensitive;
import com.zhiflow.common.core.sensitive.SensitiveTypeEnum;

import com.zhiflow.mall.common.dto.OrderTotalDTO;
import com.zhiflow.weixin.common.entity.WxUser;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 商城用户
 *
 * @date 2019-12-04 11:09:55
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_info")
@Schema(description = "商城用户")
public class UserInfo extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
	/**
	 * 用户编码
	 */
	@Schema(description = "用户编码")
	private Integer userCode;
	/**
	 * 手机号码
	 */
	@Sensitive(type = SensitiveTypeEnum.MOBILE_PHONE)
	@Schema(description = "手机号码")
	private String phone;
	/**
	 * 用户名（可空：手机号注册、微信网页授权建的账号没有用户名）。租户内唯一、不区分大小写，入库前转小写。
	 */
	@Schema(description = "用户名")
	private String username;
	/**
	 * 邮箱。可作为登录标识（短信验证码不可用时）。
	 *
	 * <p>⚠️ 入库前一律 trim + 转小写，
	 * 库上有部分唯一索引兜底；别在别处写第二种规范化，否则
	 * Foo@X.com 和 foo@x.com 会变成两个账号。
	 *
	 * <p>phone 与 email 都可空：手机号租户的用户没有 email，邮箱租户的用户没有 phone。
	 */
	@Sensitive(type = SensitiveTypeEnum.EMAIL)
	@Schema(description = "邮箱")
	private String email;
	/**
	 * 密码
	 */
	@Schema(description = "密码")
	@com.fasterxml.jackson.annotation.JsonIgnore
	private String password;

	/**
	 * 是否已设置登录密码（只读，由 password 推出，不落库）。前端据此区分「设置密码」与「修改密码」：
	 * 未设置时改密接口不要求原密码。
	 */
	@Schema(description = "是否已设置登录密码", accessMode = Schema.AccessMode.READ_ONLY)
	@com.fasterxml.jackson.annotation.JsonProperty(value = "passwordSet", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
	public boolean isPasswordSet() {
		return password != null && !password.isBlank();
	}
	/**
	 * 来源应用（MA：小程序；H5：普通H5；H5-WX：微信H5；APP：app；H5-PC：PC端H5）
	 */
	@Schema(description = "来源应用类型")
	private String appType;
	/**
	 * 来源应用id
	 */
	@Schema(description = "来源应用id")
	private String appId;
	/**
	 * 昵称
	 */
	@Schema(description = "昵称")
	private String nickName;
	/**
	 * 性别（1：男，2：女，0：未知）
	 */
	@Schema(description = "性别")
	private String sex;

	/**
	 * 头像
	 */
	@Schema(description = "头像")
	private String headimgUrl;
	/**
	 * 所在城市
	 */
	@Schema(description = "所在城市")
	private String city;
	/**
	 * 所在国家
	 */
	@Schema(description = "所在国家")
	private String country;
	/**
	 * 所在省份
	 */
	@Schema(description = "所在省份")
	private String province;
	/**
	 * 地址
	 */
	@Schema(description = "地址")
	private String address;
	/**
	 * 版本号
	 */
	@Version
	private Integer version;

	/**
	 * 黑名单标志：'0' 正常（默认）；'1' 拉黑。真实列（V70 新增 black_flag）。
	 * 登录/下单等拉黑拦截由上层读取本字段实现，本实体只承载读写。
	 */
	@Schema(description = "黑名单标志 0-正常 1-拉黑")
	@TableField("black_flag")
	private String blackFlag;

	@TableField(exist = false)
	private String thirdSession;
	@TableField(exist = false)
	private Double lastLoginTime;
	@TableField(exist = false)
	private WxUser wxUser;
	@TableField(exist = false)
	private OrderTotalDTO orderTotal;

	/**
	 * 完整手机号（不脱敏）
	 */
	@Schema(description = "完整手机号")
	@TableField(exist = false)
	private String fullPhone;



	private transient String keyWord;

	/**
	 * 是否是新用户
	 */
	@TableField(exist = false)
	private boolean newUser;

	/**
	 * 最后登录IP地址
	 */
	@Schema(description = "最后登录IP地址")
	private String lastLoginIp;

	/**
	 * 注册IP地址
	 */
	@Schema(description = "注册IP地址")
	private String registerIp;

	/**
	 * 设备类型（iOS/Android/PC/WeChat等）
	 */
	@Schema(description = "设备类型")
	private String deviceType;

	/**
	 * 设备信息（设备型号、操作系统版本等）
	 */
	@Schema(description = "设备信息")
	private String deviceInfo;

	/**
	 * 最后登录时间
	 */
	@Schema(description = "最后登录时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime lastLoginTime2;
}
