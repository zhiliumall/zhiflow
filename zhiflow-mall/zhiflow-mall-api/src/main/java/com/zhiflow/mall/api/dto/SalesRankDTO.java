package com.zhiflow.mall.api.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SalesRankDTO {
    private String userId;  //用户id
    private String nickName; //用户姓名
    private BigDecimal totalSales; //销售总金额
    private BigDecimal totalReward; //总奖励金额
    private Long orderCount; //周期窗口内团队出单数（distribution_level='1' 的推广订单行数）
    private Long rankNum;  //排名
    private String headimgUrl; //头像
}
