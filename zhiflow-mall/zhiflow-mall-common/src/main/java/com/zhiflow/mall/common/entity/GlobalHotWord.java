/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;


/**
 * 全局热词
 *
 * @date 2025-07-26 21:41:43
 */
@Data
@TableName("global_hot_word")
@Schema(description = "全局热词")
public class GlobalHotWord implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

	/**
	 *
	 */
	@TableId(type = IdType.ASSIGN_ID)
	@NotNull(message = "不能为空")
	@Schema(description = "")
	// 雪花 id 有 19 位，超过 JS 的安全整数范围；按字符串输出，前端拿到的才是原值
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;
	/**
	 * 所属租户
	 */
	@Schema(description = "所属租户")
	@TableField(fill = FieldFill.INSERT, updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
	private String tenantId;
	/**
	 * 热词内容
	 */
	@NotNull(message = "热词内容不能为空")
	@Schema(description = "热词内容")
	private String word;
	/**
	 * 总搜索次数
	 */
	@NotNull(message = "总搜索次数不能为空")
	@Schema(description = "总搜索次数")
	private Long totalCount;
	/**
	 * 逻辑删除标记（0：显示；1：隐藏）
	 */
	@Schema(description = "逻辑删除标记（0：显示；1：隐藏）")
	@TableField(fill = FieldFill.INSERT)
	private String delFlag;
	/**
	 * 创建时间
	 */
	@Schema(description = "创建时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@TableField(fill = FieldFill.INSERT)
	private LocalDateTime createTime;
	/**
	 * 最后更新时间
	 */
	@Schema(description = "最后更新时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@TableField(fill = FieldFill.INSERT_UPDATE)
	private LocalDateTime updateTime;

}
