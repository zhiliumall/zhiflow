/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import io.swagger.v3.oas.annotations.media.Schema;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 存储配置
 *
 * @date 2020-02-03 20:07:38
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_config_storage")
@Schema(description = "存储配置")
public class SysConfigStorage extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 是否自配服务（1：是；0：否，用户平台存储配置）
     */
    @Schema(description = "是否自配服务（1：是；0：否，用户平台存储配置）")
    private String isSelf;
    /**
     * 存储类型：0 本地，3 MinIO
     */
	@Schema(description = "存储类型：0 本地，3 MinIO")
    private String storageType;
    /**
     * 地域节点
     */
	@Schema(description = "地域节点")
    private String endpoint;
    /**
     * accessKeyId
     */
	@Schema(description = "accessKeyId")
    private String accessKeyId;
    /**
     * 密钥
     */
	@Schema(description = "密钥")
    private String accessKeySecret;
    /**
     * 域名
     */
	@Schema(description = "域名")
    private String bucket;
	/**
	 * 图片水印内容
	 */
	@Schema(description = "图片水印内容")
    private String waterMarkContent;
}
