/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.weixin.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import com.zhiflow.common.core.sensitive.Sensitive;
import com.zhiflow.common.core.sensitive.SensitiveTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 微信用户
 *
 * @date 2019-03-25 15:39:39
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "微信用户")
@TableName("wx_user")
public class WxUser extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
   * 创建者
   */
    private String createId;
    /**
   * 更新者
   */
    private String updateId;
    /**
   * 公众号配置ID、小程序AppID
   */
	@Schema(description = "公众号配置ID、小程序AppID")
    private String appId;
    /**
   * 应用类型(1:小程序，2:公众号)
   */
	@Schema(description = "应用类型")
    private String appType;
    /**
   * 用户标识
   */
	@Schema(description = "用户标识")
    private String openId;

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
   * 手机号码
   */
	@Schema(description = "手机号码")
	@Sensitive(type = SensitiveTypeEnum.MOBILE_PHONE)
    private String phone;
    /**
   * 头像
   */
	@Schema(description = "头像")
    private String headimgUrl;
    /**
   * union_id
   */
	@Schema(description = "union_id")
    private String unionId;
    /**
   * 会话密钥
   */
	@Schema(description = "会话密钥")
    private String sessionKey;
	/**
	 * 商城用户ID
	 */
	@Schema(description = "商城用户ID")
	private String mallUserId;
}
