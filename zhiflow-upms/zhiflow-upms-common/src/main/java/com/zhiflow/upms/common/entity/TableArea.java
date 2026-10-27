/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serial;
import java.io.Serializable;

/**
 * 地区表
 *
 * @date 2023-10-10 15:51:20
 */
@Data
@TableName("table_area")
@Schema(description = "地区表")
public class TableArea implements Serializable {
    @Serial
    private static final long serialVersionUID=1L;

    /**
     * ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    @NotNull(message = "ID不能为空")
    @Schema(description = "ID")
    private String id;
    /**
     * 父id
     */
    @Schema(description = "父id")
    private String pid;
    /**
     * 简称
     */
    @Schema(description = "简称")
    private String shortname;
    /**
     * 名称
     */
    @Schema(description = "名称")
    private String name;
    /**
     * 全称
     */
    @Schema(description = "全称")
    private String mergename;
    /**
     * 层级 0 1 2 省市区县
     */
    @Schema(description = "层级 0 1 2 省市区县")
    private Integer level;
    /**
     * 拼音
     */
    @Schema(description = "拼音")
    private String pinyin;
    /**
     * 长途区号
     */
    @Schema(description = "长途区号")
    private String code;
    /**
     * 邮编
     */
    @Schema(description = "邮编")
    private String zip;
    /**
     * 首字母
     */
    @Schema(description = "首字母")
    private String first;
    /**
     * 经度
     */
    @Schema(description = "经度")
    private String lng;
    /**
     * 纬度
     */
    @Schema(description = "纬度")
    private String lat;

}
