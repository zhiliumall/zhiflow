package com.zhiflow.mall.api.config;

import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.resource.ResourceStore;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
public class CaptchaResourceConfiguration {

    private final ResourceStore resourceStore;

    public CaptchaResourceConfiguration(ResourceStore resourceStore) {
        this.resourceStore = resourceStore;
    }

    @PostConstruct
    public void init() throws Exception {
        // 背景图片存放在 resource/bgimages 目录下
        String backgroundDirectory = "bgimages";
        List<String> backgroundList = new ArrayList<>();

        String pattern = "classpath*:" + backgroundDirectory + "/**";
        ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
        org.springframework.core.io.Resource[] resources = resourcePatternResolver.getResources(pattern);
        for (org.springframework.core.io.Resource resource : resources) {
            String path = resource.getURL().getPath();
            if (!path.endsWith("/")) {
                String background = path.substring(path.lastIndexOf(backgroundDirectory));
                backgroundList.add(background);
            }
        }

        // ⚠️ 空资源必须启动即崩，不能放过。
        //
        // 2026-08-09 事故：有人换验证码背景图时删了旧的 11 张、新的 6 张忘了 git add。
        // 本地工作区有图，构建正常；CI 拿干净检出打出来的 jar 里
        // bgimages 整个目录不存在 —— 服务照常启动，只有 log 里多了几行「Background image:」没打印。
        // 直到有人访问 /captcha/generate 才 500（"store中资源为空"），而登录页刚接了
        // 图形验证码闸，于是**两个后台同时登不进去**，没人立刻联想到是打包漏了静态资源。
        //
        // 为什么选「启动崩」而不是「打日志」：这个目录是随包走的静态资源，唯一空的原因就是
        // 打包出错。启动崩会被部署健康检查当场抓到并回滚；启动成功但登不上，是更坏的失败模式。
        if (backgroundList.isEmpty()) {
            throw new IllegalStateException(
                    "验证码背景图为空：classpath 下找不到 " + backgroundDirectory + "/**。"
                            + "多半是图片没进版本库（换图时删了旧的、新的忘了 git add），"
                            + "或打包时未 clean。资源目录在 zhiflow-mall-common/src/main/resources/bgimages。");
        }

        backgroundList.forEach((String background) -> {
            // 添加自定义背景图片, Resource 接收三个参数
            // 第一个参数为资源类型, 支持 classpath file url 三种类型
            // 第二个参数为资源路径
            // 第三个参数为资源的标签, 默认为 default
            Resource resource = new Resource("classpath", background);
            resourceStore.addResource(CaptchaTypeConstant.CONCAT, resource);
            resourceStore.addResource(CaptchaTypeConstant.SLIDER, resource);
            resourceStore.addResource(CaptchaTypeConstant.ROTATE, resource);
            resourceStore.addResource(CaptchaTypeConstant.WORD_IMAGE_CLICK, resource);
        });

        // 打印最终的背景列表
        backgroundList.forEach(background -> log.info("Background image: {}", background));
    }

}
