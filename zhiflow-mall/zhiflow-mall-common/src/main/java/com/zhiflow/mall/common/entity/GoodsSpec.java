/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import com.zhiflow.mall.common.vo.GoodsSpecLeafVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 规格
 *
 * @date 2019-08-13 16:10:54
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goods_spec")
@Schema(description = "规格")
public class GoodsSpec extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
   * 名称
   */
	@Schema(description = "名称")
    private String name;

	@TableField(exist = false)
	private String spuId;

	@TableField(exist = false)
	private String value;

	@TableField(exist = false)
	private List<GoodsSpecLeafVO> leaf;

}
