package com.zhiflow.mall.common.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode
public class CaptchaTrackDto {
    private String id;
    private CustomImageCaptchaTrack data;
}