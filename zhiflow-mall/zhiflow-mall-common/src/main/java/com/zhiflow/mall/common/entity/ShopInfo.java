/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.zhiflow.common.core.entity.base.TenantEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 商城设置（单店铺：基线里只有一个默认店铺，后台「商城设置」只改名称、logo、客服电话）
 *
 * @date 2020-05-15 13:35:18
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("shop_info")
@Schema(description = "商城设置")
public class ShopInfo extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
	/**
	 * （1：开启；0：关闭）
	 */
	@Schema(description = "1：开启；0：关闭")
	private String enable;
    /**
     * 客服电话
     */
    @NotNull(message = "客服电话不能为空")
    @Schema(description = "客服电话")
    private String phone;
    /**
     * 商城名称
     */
    @NotNull(message = "商城名称不能为空")
    @Schema(description = "商城名称")
    private String name;
    /**
     * logo
     */
    @NotNull(message = "logo 不能为空")
    @Schema(description = "logo")
    private String imgUrl;
}
