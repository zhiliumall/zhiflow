package com.zhiflow.mall.common.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单摘要投影。客服工作台「最近订单」列表用，省掉 OrderInfo 实体里
 * 60+ 个字段 + items 嵌套 list 的体积。
 */
@Data
@Schema(description = "订单摘要")
public class OrderSummaryVO {

    @Schema(description = "订单 ID")
    private String id;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "状态码")
    private String status;

    @Schema(description = "状态展示文本")
    private String statusLabel;

    @Schema(description = "支付方式")
    private String paymentWay;

    @Schema(description = "实付金额")
    private BigDecimal paymentPrice;

    @Schema(description = "运费")
    private BigDecimal freightPrice;

    @Schema(description = "下单时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "支付时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime paymentTime;

    /**
     * 取消时间（V19 新增列）。客服工作台行为轨迹的「取消订单」事件用。
     * <p>历史订单（V19 之前取消的）该列为 NULL —— 调用方用 {@link #updateTime} 兜底近似。
     */
    @Schema(description = "取消时间（status=5；历史数据可能为 null）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime cancelTime;

    /** 仅用于 cancelTime 为 null 时的取消时间兜底，不作他用（任何一次 update 都会刷新它）。 */
    @Schema(description = "最后更新时间（cancelTime 的兜底）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    @Schema(description = "主商品名（订单显示用，name 字段）")
    private String name;

    @Schema(description = "主商品图")
    private String mainImage;

    @Schema(description = "商品总件数")
    private Integer goodsQty;
}
