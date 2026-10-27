/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import com.zhiflow.common.core.entity.base.TenantEntity;
import com.zhiflow.common.core.mybatis.typehandler.ArrayStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.ibatis.type.JdbcType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 商品类目
 *
 * @date 2019-08-12 11:46:28
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goods_category")
@Schema(description = "商品类目")
public class GoodsCategory extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

	/**
	 * （1：开启；0：关闭）
	 */
	@Schema(description = "1：开启；0：关闭")
	private String enable;
	/**
	 * 父分类编号
	 */
	@Schema(description = "父分类编号")
	private String parentId;
	/**
	 * 名称
	 */
	@Schema(description = "名称")
	private String name;
	/**
	 * 描述
	 */
	@Schema(description = "描述")
	private String description;
	/**
	 * 图片
	 */
	@Schema(description = "图片")
	private String picUrl;
	/**
	 * 移动端图片
	 */
	private String mobilePicUrl;
	/**
	 * 排序
	 */
	@Schema(description = "排序")
	private Integer sort;
	/**
	 * 跳转页面
	 */
	@Schema(description = "跳转页面")
	private String page;

	/**
	 * 平台
	 */
	@Schema(description = "平台")
	@TableField(typeHandler = ArrayStringTypeHandler.class, jdbcType= JdbcType.VARCHAR)
	private String[] supportPlatforms;

	private transient List<GoodsSpu> spu ;


	private String mobileAdImageUrl;

	private String pcBigImageUrl;

}
