package com.zhiflow.mall.admin.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TeamAmountDTO {

    //奖励总金额
    private BigDecimal rewardTotalAmount;

    //未发放金额
    private BigDecimal grantedAmount;

    //已发放金额
    private BigDecimal ungrantedAmount;




}
