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
 * 素材
 *
 * @date 2019-10-26 19:23:45
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("material")
@Schema(description = "素材")
public class Material extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 所属类型：0：本商城（开源版只用这一种）
     */
    @Schema(description = "所属类型：0：本商城（开源版只用这一种）")
    private String ofType;
    /**
     * 所属店铺 ID
     */
    @Schema(description = "所属店铺 ID")
	private String shopId;
    /**
     * 创建者ID
     */
	@Schema(description = "创建者ID")
    private String createId;
    /**
     * 类型1、图片；2、视频
     */
	@Schema(description = "类型1、图片；2、视频")
    private String type;
    /**
     * 分组ID
     */
	@Schema(description = "分组ID")
    private String groupId;
    /**
     * 素材名
     */
	@Schema(description = "素材名")
    private String name;
    /**
     * 素材链接
     */
	@Schema(description = "素材链接")
    private String url;

}
