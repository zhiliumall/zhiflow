package com.zhiflow.mall.common.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode
public class CaptchaGenerateDto {
    private String type;
}