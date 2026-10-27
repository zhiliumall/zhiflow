/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.entity.base.TenantEntity;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.enums.OrderInfoEnum;
import com.zhiflow.weixin.common.entity.WxApp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 商城订单
 *
 * @date 2019-09-10 15:21:22
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("order_info")
@Schema(description = "商城订单")
public class OrderInfo extends TenantEntity {

    @Schema(description = "PK")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

	/**
	 * 店铺Id
	 */
	@Schema(description = "店铺Id")
	private String shopId;
	/**
	 * 来源应用（MA：小程序；H5：普通H5；H5-WX：微信H5；APP：app；H5-PC：PC端H5）
	 */
	@Schema(description = "应用类型")
	private String appType;
	/**
	 * 应用ID
	 */
	@Schema(description = "应用ID")
	private String appId;
	/**
	 * 用户id（线下收银时为后台用户ID。否则为商城用户ID）
	 */
	@Schema(description = "用户id（线下收银时为后台用户ID。否则为商城用户ID）")
	private String userId;
	/**
	 * 订单单号
	 */
	@Schema(description = "订单单号")
	private String orderNo;
	/**
	 * 销售金额
	 */
	@Schema(description = "销售金额")
	private BigDecimal salesPrice;
	/**
	 * 运费金额
	 */
	@Schema(description = "运费金额")
	private BigDecimal freightPrice;
	/**
	 * 支付方式1、货到付款；2、在线支付
	 */
	@Schema(description = "支付方式")
	private String paymentWay;
	/**
	 * 配送方式1、普通快递；2、上门自提；3、线下收银
	 */
	@Schema(description = "配送方式")
	private String deliveryWay;
	/**
	 * 付款方式1、微信支付；2、支付宝支付；3、余额支付
	 */
	@Schema(description = "付款方式1、微信支付；2、支付宝支付；3、余额支付")
	private String paymentType;
	/**
	 * 交易类型（JSAPI--公众号支付、移动H5、小程序；NATIVE--原生扫码支付；APP--app支付）
	 */
	@Schema(description = "交易类型（JSAPI--公众号支付、移动H5、小程序；NATIVE--原生扫码支付；APP--app支付）")
	private String tradeType;
	/**
	 * 支付金额（销售金额+运费金额-积分抵扣金额-电子券抵扣金额-用户等级折扣金额）
	 */
	@Schema(description = "支付金额（销售金额+运费金额-积分抵扣金额-电子券抵扣金额-用户等级折扣金额）")
	private BigDecimal paymentPrice;
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
	 * 付款时间
	 */
	@Schema(description = "付款时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime paymentTime;
	/**
	 * 收货时间
	 */
	@Schema(description = "收货时间")
	private LocalDateTime receiverTime;
	/**
	 * 成交时间
	 */
	@Schema(description = "成交时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime closingTime;
	/**
	 * 取消时间（V19 新增列）。
	 * <p>此前没有取消时间列，只能拿 update_time 近似 —— 而取消后任何一次 update（退款、后台改备注）
	 * 都会覆盖它。历史订单该列为 NULL，读侧用 COALESCE(cancel_time, update_time) 兜底。
	 * <p><b>写入点是「把 status 置为 5 的那 4 处」，靠人工维护，不是数据库/框架自动写的</b>
	 * （曾漏掉整单全退这一处，导致这类单的取消时间仍会漂移）：
	 * <ul>
	 *   <li>mall-api {@code OrderInfoServiceImpl} — 用户取消（V1 回补 / V2 路径）2 处</li>
	 *   <li>mall-admin {@code OrderInfoServiceImpl} — 后台取消</li>
	 *   <li>mall-admin {@code OrderRefundsServiceImpl} — 整单全退 → 取消整单</li>
	 * </ul>
	 * <b>新增任何把 status 置 5 的代码路径，必须同时写本列</b>，否则行为轨迹的「取消订单」事件时间会漂。
	 * <p>注意与 {@link #closingTime}（成交时间，用户评价时写入）语义完全不同，别混用。
	 */
	@Schema(description = "取消时间（status=5 时写入；历史数据可能为 null）")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime cancelTime;
	/**
	 * 备注
	 */
	@Schema(description = "备注")
	private String remark;
	/**
	 * 商家备注（V71）。管理端订单详情读写，与通用 remark（内部备注）、user_message（买家留言）语义分离。
	 */
	@Schema(description = "商家备注")
	private String merchantRemark;
	/**
	 * 是否支付0、未支付 1、已支付
	 */
	@Schema(description = "是否支付0、未支付 1、已支付")
	// 第一层：updateById / saveOrUpdate / update(entity, wrapper) 整行写回时跳过本列，
	// 只有状态机的显式条件 UPDATE 能改（OrderStateMachine → OrderStateMachineMapper#compareAndSet）。
	// 插入不受影响（insertStrategy 仍为默认），建单照常写 is_pay='0'。
	@TableField(updateStrategy = FieldStrategy.NEVER)
	private String isPay;
	/**
	 * 订单名
	 */
	@Schema(description = "订单名")
	private String name;
	/**
	 * 订单状态1、待发货 2、待收货 3、确认收货/已完成 5、已关闭 10、拼团中
	 */
	@Schema(description = "订单状态1、待发货 2、待收货 3、确认收货/已完成 5、已关闭 10、拼团中")
	// 第一层：同 isPay。整行写回旧快照（发起支付、改价、后台取消把刚付的单写回未付）
	// 从此在实体层就被挡住；状态只能经状态机改。
	@TableField(updateStrategy = FieldStrategy.NEVER)
	private String status;

	private transient List<String> statusList;
	/**
	 * 买家留言
	 */
	@Schema(description = "买家留言")
	private String userMessage;
	/**
	 * 支付交易ID
	 */
	@Schema(description = "支付交易ID")
	private String transactionId;
	/**
	 * 订单类型（0、普通订单；1、砍价订单；2、拼团订单；3、秒杀订单；4、限时订单；5、新人专享；6、分销订单；-1、混合类型）
	 */
	@Schema(description = "订单类型（0、普通订单；1、砍价订单；2、拼团订单；3、秒杀订单；4、限时订单；5、新人专享；6、分销订单；-1、混合类型）")
	private String orderType;
	/**
	 * 收件人姓名
	 */
	@Schema(description = "收件人姓名")
	private String recName;
	/**
	 * 收件人电话
	 */
	@Schema(description = "收件人电话")
	private String recTel;
	/**
	 * 收件人地址
	 */
	@Schema(description = "收件人地址")
	private String recAddress;
	/**
	 * 地区id
	 */
	@Schema(description = "地区id")
	private String areaId;

	@Schema(description = "订单详情")
	@TableField(exist = false)
	private List<OrderItem> listOrderItem;

	/**
	 * 这张单当前可做的操作（由跳转表算出，含置灰原因）。只在买家订单详情 / 列表、后台订单详情里填，
	 * 前端按它渲染按钮、不再按状态码写判断。
	 */
	@TableField(exist = false)
	private List<com.zhiflow.mall.common.trade.statemachine.order.OrderActionResolver.ActionView> actions;

	@Schema(description = "订单记录")
	@TableField(exist = false)
	private List<OrderRecord> listOrderRecord;

	/**
	 * 用户信息
	 */
	@TableField(exist = false)
	private UserInfo userInfo;
	/**
	 * 订单来源
	 */
	@TableField(exist = false)
	private WxApp app;
	/**
	 * 订单状态过期时间
	 */
	@TableField(exist = false)
	private Long outTime;
	/**
	 * 状态0、待付款 1、待发货 2、待收货 3、已完成 4、已关闭
	 */
	@TableField(exist = false)
	private String statusDesc;
	/**
	 * 订单类型描述（0、普通订单；1、砍价订单；2、拼团订单；3、秒杀订单；4、限时订单；5、新人专享；6、分销订单；-1、混合类型）
	 */
	@TableField(exist = false)
	private String orderTypeDesc;

	/**
	 * 顾客在结算页选的具体支付方式（发起支付时透传，不落 order_info 表）。为空则由渠道自行决定。
	 */
	@TableField(exist = false)
	private String payMethod;

	/**
	 * 支付结果返回地址（支付宝）
	 */
	@TableField(exist = false)
	private String returnUrl;
	/**
	 * 用户付款中途退出返回商户网站的地址（支付宝）
	 */
	@TableField(exist = false)
	private String quitUrl;

	/**
	 * 支付用的openId（临时借用，不持久化）
	 */
	@TableField(exist = false)
	private String payOpenId;

	/**
	 * 支付状态机 v1.2 阶段 0：入账消息里渠道确认的实收金额 / 币种 / 支付单号（非表字段，不序列化给前端）。
	 * 只在支付回调消费 → {@code notifyOrder} 这一段传递，用于差错池记录迟到 / 重复到账的真实金额。
	 */
	@TableField(exist = false)
	@com.fasterxml.jackson.annotation.JsonIgnore
	private transient java.math.BigDecimal channelPaidAmount;

	@TableField(exist = false)
	@com.fasterxml.jackson.annotation.JsonIgnore
	private transient String channelPaidCurrency;

	@TableField(exist = false)
	@com.fasterxml.jackson.annotation.JsonIgnore
	private transient String channelPayNo;

	@TableField(exist = false)
	@com.fasterxml.jackson.annotation.JsonIgnore
	private transient String channelCallbackLogId;

	@DateTimeFormat(pattern="yyyy-MM-dd HH:mm:ss")
	@TableField(exist = false)
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime beginTime;

	@DateTimeFormat(pattern="yyyy-MM-dd HH:mm:ss")
	@TableField(exist = false)
	private LocalDateTime endTime;

	@TableField(exist = false)
	private ShopInfo shopInfo;

	/**
	 * 查询条件：应用名称（模糊）。order_info 只存 app_id，应用名在 zhiflow_wx.wx_app.name，
	 * 由后台订单分页 SQL 用 EXISTS 子查询按名称反查 app_id，不落库。
	 */
	@Schema(description = "查询条件：应用名称（模糊）")
	@TableField(exist = false)
	private String appName;

	private transient String keyword;
	private transient String skuId;
	private transient String spuId;

	@Schema(description = "订单物流状态")
	private String logisticStatus;

	public String getStatusDesc() {
		if (CommonConstants.NO.equals(this.isPay) && this.status == null) {
			return "待付款";
		}
		if (this.status == null) {
			return null;
		}
		return OrderInfoEnum.getDesc(this.status.strip());
	}

	public String getOrderTypeDesc() {
		if (this.orderType == null) {
			return null;
		}
		return "0".equals(this.orderType) ? "普通订单" : "未知";
	}

	/**
	 * 进入当前状态的时刻。只由状态机的条件更新写（now()），不参与插入 / 整行更新；
	 * 上线前已在当前状态的行为空，时效任务按状态取回退列（见 {@code StateSince}）。
	 */
	@TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
			updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
	@com.fasterxml.jackson.annotation.JsonIgnore
	private java.time.OffsetDateTime stateSince;

	/** 发货时刻（V118，由 O10 的条件更新写）。14.5 待收货的时效从这里起算。只读。 */
	@TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
			updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
	@com.fasterxml.jackson.annotation.JsonIgnore
	private java.time.OffsetDateTime deliveryTime;
}
