package com.zhiflow.mall.common.dto;

import lombok.Data;

@Data
public class OrderStatiscisDTO {
    private long nowOrderCount;
    private long yesOrderCount;
    private String period;
}
