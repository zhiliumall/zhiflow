package com.zhiflow.mall.common.dto;

import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.zhiflow.mall.common.utils.CustomJsonDateDeserializer;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

@Data
public class CustomImageCaptchaTrack extends ImageCaptchaTrack {
    @JsonDeserialize(using = CustomJsonDateDeserializer.class)
    private Date startTime;
    @JsonDeserialize(using = CustomJsonDateDeserializer.class)
    private Date stopTime;
}