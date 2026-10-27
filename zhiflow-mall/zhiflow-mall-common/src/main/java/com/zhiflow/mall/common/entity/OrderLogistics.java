/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 订单物流
 *
 * @date 2019-09-16 09:53:17
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("order_logistics")
@Schema(description = "订单物流")
public class OrderLogistics extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 邮编
     */
	@Schema(description = "邮编")
    private String postalCode;
    /**
     * 收货人名字
     */
	@Schema(description = "收货人名字")
    private String recName;
    /**
     * 收货人电话号码
     */
	@Schema(description = "收货人电话号码")
    private String recTel;
    /**
     * 收货人详细地址
     */
	@Schema(description = "收货人详细地址")
    private String recAddress;
    /**
     * 物流公司编码
     */
	@Schema(description = "物流公司编码")
    private String logisticsCode;
	/**
	 * 物流公司名称
	 */
	@Schema(description = "物流公司名称")
	private String logisticsName;
	/**
	 * 订单ID
	 */
	@Schema(description = "订单ID")
	private String orderId;
    /**
     * 物流单号
     */
    private String logisticsNo;

}
