package com.zhiflow.mall.api.controller;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.application.vo.CaptchaResponse;
import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.common.response.ApiResponse;
import cloud.tianai.captcha.spring.plugins.secondary.SecondaryVerificationApplication;
import com.alibaba.fastjson2.JSON;
import com.google.common.collect.Lists;
import com.zhiflow.common.core.exception.CheckedException;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.mall.common.dto.CaptchaGenerateDto;
import com.zhiflow.mall.common.dto.CaptchaTrackDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@ApiLogin(mustLogin = false)
@RestController
@RequestMapping("captcha")
@Tag(description = "captcha", name = "滑块验证码")
public class CaptchaController {
    private static final List<String> types = Lists.newArrayList(CaptchaTypeConstant.SLIDER, CaptchaTypeConstant.ROTATE, CaptchaTypeConstant.CONCAT, CaptchaTypeConstant.WORD_IMAGE_CLICK);

    private final ImageCaptchaApplication imageCaptchaApplication;
    
    @Autowired(required = false)
    private SecondaryVerificationApplication secondaryVerificationApplication;

    public CaptchaController(ImageCaptchaApplication imageCaptchaApplication) {
        this.imageCaptchaApplication = imageCaptchaApplication;
    }

    // 主要用于H5用户登录，找回密码，申请代理
    @Operation(summary = "生成验证码")
    @RequestMapping("generate")
    @ResponseBody
    public CaptchaResponse<ImageCaptchaVO> generate(CaptchaGenerateDto captchaGenerateDto) {
        String type = captchaGenerateDto.getType();
        if (StringUtils.isBlank(type)) {
            type = CaptchaTypeConstant.SLIDER;
        }

        if (!types.contains(type)) {
            throw new CheckedException("不支持的验证码类型：" + type);
        }

        if ("RANDOM".equals(type)) {// 如果是随机
            int i = ThreadLocalRandom.current().nextInt(0, 4);
            type = types.get(i);
        }

        // 根据验证码类型生成对应的验证码
        CaptchaResponse<ImageCaptchaVO> captchaResponse = imageCaptchaApplication.generateCaptcha(type);

        log.info("验证码唯一标识: {}", captchaResponse.getId());

        return captchaResponse;
    }

    @Operation(summary = "校验验证码")
    @PostMapping("check")
    @ResponseBody
    public ApiResponse<?> check(@RequestBody CaptchaTrackDto captchaTrackDto) {
        log.info("captcha check 请求参数: {}", JSON.toJSONString(captchaTrackDto));
        ApiResponse response = imageCaptchaApplication.matching(captchaTrackDto.getId(), captchaTrackDto.getData());
        response.setData(captchaTrackDto.getId());
        return response;
    }
}
