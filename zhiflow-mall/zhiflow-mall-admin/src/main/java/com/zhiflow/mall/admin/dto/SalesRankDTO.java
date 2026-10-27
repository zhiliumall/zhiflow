package com.zhiflow.mall.admin.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SalesRankDTO {
    private String userId;  //用户id
    private String nickName; //用户姓名
    private BigDecimal totalSales; //销售总金额
    private BigDecimal totalReward; //总奖励金额
    private Long rankNum;  //排名
}
