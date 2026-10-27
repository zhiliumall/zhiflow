/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 商城订单详情
 *
 * @date 2019-09-10 15:31:40
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("order_item")
@Schema(description = "商城订单详情")
public class OrderItem extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

	/**
	 * 店铺Id
	 */
	@Schema(description = "店铺Id")
	private String shopId;
	/**
	 * 订单编号
	 */
	@Schema(description = "订单编号")
	private String orderId;
	/**
	 * 商品Id
	 */
	@Schema(description = "商品Id")
	private String spuId;
	/**
	 * 商品名
	 */
	@Schema(description = "商品名")
	private String spuName;
	/**
	 * 规格信息
	 */
	@Schema(description = "规格信息")
	private String specInfo;
	/**
	 * skuId
	 */
	@Schema(description = "skuId")
	private String skuId;
	/**
	 * 图片
	 */
	@Schema(description = "图片")
	private String picUrl;
	/**
	 * 商品数量
	 */
	@Schema(description = "商品数量")
	private Integer quantity;
	/**
	 * 购买单价
	 */
	@Schema(description = "购买单价")
	private BigDecimal salesPrice;
	/**
	 * 运费金额
	 */
	@Schema(description = "运费金额")
	private BigDecimal freightPrice;
	/**
	 * 支付金额（购买单价*商品数量+运费金额-积分抵扣金额-电子券抵扣金额）
	 */
	@Schema(description = "支付金额（购买单价*商品数量+运费金额-积分抵扣金额-电子券抵扣金额）")
	private BigDecimal paymentPrice;

	/**
	 * 商品项货款实付（不含运费）：支付成功时写一次（= 实付 − 本项运费；优惠券等已在下单时按项分摊进实付），
	 * 是售后按项可退的上限；运费只在整单退时退。<b>存量行为空</b>——读取方回落到 {@code paymentPrice}（老口径，含本项运费）。
	 * 只由支付成功路径的条件 UPDATE 写（{@code paid_amount IS NULL} 才写），实体整行写回不碰它。
	 */
	@TableField(updateStrategy = FieldStrategy.NEVER)
	private BigDecimal paidAmount;

	/**
	 * 商品项状态：NORMAL / SHIPPED / CANCELLED，见 {@code ItemStatus}。下单时写 NORMAL；之后只由发货、订单取消在同一事务里的
	 * 条件更新写（{@code ItemStatusSql}），实体整行写回不碰它。为空时读取方用 {@code ItemStatus.of} 按发货痕迹推导。
	 */
	@TableField(updateStrategy = FieldStrategy.NEVER)
	@Schema(description = "商品项状态 NORMAL/SHIPPED/CANCELLED")
	private String itemStatus;

	/** 本项发货时刻，只由发货的条件更新写。存量为空，读取方回落 delivery_time。 */
	@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@Schema(description = "本项发货时刻")
	private java.time.OffsetDateTime shippedTime;

	/**
	 * 支付积分
	 */
	@Schema(description = "支付积分")
	private Integer paymentPoints;
	/**
	 * 电子券支付金额
	 */
	@Schema(description = "电子券支付金额")
	private BigDecimal paymentCouponPrice;
	/**
	 * 积分抵扣金额
	 */
	@Schema(description = "积分抵扣金额")
	private BigDecimal paymentPointsPrice;
	/**
	 * 备注
	 */
	@Schema(description = "备注")
	private String remark;
	/**
	 * 订单物流ID
	 */
	@Schema(description = "订单物流ID")
	private String logisticsId;
	/** 物流公司名称 */
	@Schema(description = "物流公司名称")
	@TableField(exist = false)
	private String logisticsName;
	/** 物流单号 */
	@Schema(description = "物流单号")
	@TableField(exist = false)
	private String logisticsNo;	/**
	 * 发货类型（1、发物流 2、配送 3、虚拟发货）
	 */
	@Schema(description = "发货类型（1、发物流 2、配送 3、虚拟发货）")
	private String deliveryType;
	/**
	 * 发货时间
	 */
	@Schema(description = "发货时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime deliveryTime;

	@TableField(exist = false)
	private GoodsSku goodsSku;


}
