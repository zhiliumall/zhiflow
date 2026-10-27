/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.zhiflow.common.core.entity.base.TenantEntity;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;
import io.swagger.v3.oas.annotations.media.Schema;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 用户收货地址
 *
 * @date 2019-09-11 14:28:59
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_address")
@Schema(description = "用户收货地址")
public class UserAddress extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    /**
   * 用户编号
   */
	@Schema(description = "用户编号")
    private String userId;
    /**
   * 收货人名字
   */
	@Schema(description = "收货人名字")
    private String userName;
    /**
   * 邮编
   */
	@Schema(description = "邮编")
    private String postalCode;
    /**
   * 省名
   */
	@Schema(description = "省名")
    private String provinceName;
    /**
   * 市名
   */
	@Schema(description = "市名")
    private String cityName;
    /**
   * 区名
   */
	@Schema(description = "区名")
    private String countyName;
    /**
   * 详情地址
   */
	@Schema(description = "详情地址")
    private String detailInfo;
    /**
     * 地区id
     */
    @Schema(description = "地区id")
    private String areaId;
    /**
     * 经度（GCJ-02 坐标系）
     */
    @Schema(description = "经度")
    private Double longitude;

    /**
     * 纬度（GCJ-02 坐标系）
     */
    @Schema(description = "纬度")
    private Double latitude;
    /**
    * 电话号码
    */
	@Schema(description = "电话号码")
    private String telNum;
    /**
   * 是否默认 1是0否
   */
	@Schema(description = "是否默认")
    private String isDefault;
  
}
