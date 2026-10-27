package com.zhiflow.mall.api.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 用户消费金额及排名 DTO
 */
@Data
public class UserConsumptionWithRankDTO {
    private String userId;
    private BigDecimal totalConsumption; // 消费总额（已处理 NULL）
    private Integer rank; // 排名
    private String distributionUserId; //分销商
}
