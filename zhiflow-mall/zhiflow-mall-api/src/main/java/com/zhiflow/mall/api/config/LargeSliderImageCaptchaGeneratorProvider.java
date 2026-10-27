package com.zhiflow.mall.api.config;

import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.generator.ImageCaptchaGenerator;
import cloud.tianai.captcha.generator.ImageCaptchaGeneratorProvider;
import cloud.tianai.captcha.generator.ImageTransform;
import cloud.tianai.captcha.generator.impl.StandardSliderImageCaptchaGenerator;
import cloud.tianai.captcha.interceptor.CaptchaInterceptor;
import cloud.tianai.captcha.resource.ImageCaptchaResourceManager;
import org.springframework.stereotype.Component;

/**
 * 覆盖默认滑块验证码生成器，改为使用放大模板的实现。
 */
@Component
public class LargeSliderImageCaptchaGeneratorProvider implements ImageCaptchaGeneratorProvider {

    @Override
    public ImageCaptchaGenerator get(ImageCaptchaResourceManager resourceManager,
                                     ImageTransform imageTransform,
                                     CaptchaInterceptor interceptor) {
        return new StandardSliderImageCaptchaGenerator(resourceManager, imageTransform, interceptor);
    }

    @Override
    public String getType() {
        return CaptchaTypeConstant.SLIDER;
    }
}
