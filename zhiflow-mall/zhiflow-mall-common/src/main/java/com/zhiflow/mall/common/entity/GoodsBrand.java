/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;


/**
 * 商品品牌
 *
 * @date 2024-09-02 10:45:39
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goods_brand")
@Schema(description = "商品品牌")
public class GoodsBrand extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 排序字段
     */
    @Schema(description = "排序字段")
    private Integer sort;
    /**
     * （1：开启；0：关闭）
     */
    @NotNull(message = "（1：开启；0：关闭）不能为空")
    @Schema(description = "（1：开启；0：关闭）")
    private String enable;
    /**
     * 品牌名称
     */
    @NotNull(message = "品牌名称不能为空")
    @Schema(description = "品牌名称")
    private String brandName;
    /**
     * 品牌图片
     */
    @Schema(description = "品牌图片")
    private String picUrl;

}
