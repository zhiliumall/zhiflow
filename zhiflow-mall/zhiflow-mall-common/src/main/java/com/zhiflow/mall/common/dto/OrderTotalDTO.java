package com.zhiflow.mall.common.dto;

import lombok.Data;

import java.math.BigDecimal;
@Data
public class OrderTotalDTO {
    private Integer totalCount;
    private BigDecimal totalAmount;
    /**
     * 客单价（AOV）= totalAmount / totalCount，2 位小数 HALF_UP；totalCount 为 0 时置 0。
     * 由 Controller 装配（sumOrderAmountAndCountByUserId 只回 totalAmount/totalCount）。
     */
    private BigDecimal avgOrderAmount;
}
