/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.portal.config;

import com.zhiflow.common.core.constant.PathPrefixConstants;
import com.zhiflow.common.core.constant.PublicUrlConstants;
import com.zhiflow.common.portal.interceptor.ThirdSessionInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * web配置
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	// mall-api 的 @ComponentScan 不含 portal 包，ThirdSessionInterceptor 的 @Component 不会被注册为 bean，这里手动 new。
	private final RedisTemplate<String, Object> redisTemplate;

	/** 本服务的内部路径前缀；空 = 未做前缀改造，下面的排除清单原样使用。 */
	private final String pathPrefix;

	/**
	 * 显式构造器而不是 {@code @AllArgsConstructor}：本仓没配 Lombok 的
	 * {@code copyableAnnotations}，{@code @Value} 写在字段上时不会被复制到生成的构造器参数，
	 * 而生成的构造器又会把这个字段算进去 ⇒ Spring 会去找一个 String 类型的 bean 然后启动失败。
	 */
	public WebConfig(RedisTemplate<String, Object> redisTemplate,
			@Value(PathPrefixConstants.PLACEHOLDER) String pathPrefix) {
		this.redisTemplate = redisTemplate;
		this.pathPrefix = pathPrefix;
	}

	/**
	 * 拦截器。
	 *
	 * <p>⚠️ [P2.3] 排除清单必须跟着内部路径前缀走。{@link PublicUrlConstants#PORTAL_PUBLIC_URLS}
	 * 全是 mall-api 自己的 controller，打开前缀后真实路径变成
	 * {@code /mallapi/orderinfo/notify-order-wx/*}，而清单里写的是
	 * {@code /orderinfo/notify-order-wx/*} —— 匹配不上，{@code ThirdSessionInterceptor}
	 * 就会开始在这些路径上运行：带 {@code client-type: H5/H5_PC/APP} 且不带
	 * {@code tenant-id} 头的请求被判 60004 拒绝（<b>四端登录要用的 {@code /captcha/**}
	 * 正属此类</b>）；支付回调不带 {@code client-type} 头、走不拒绝的分支，
	 * 但会被塞进默认租户上下文。两种都<b>权限矩阵探不到</b>（全是 POST）。
	 *
	 * <p>{@link PublicUrlConstants#COMMON_PUBLIC_URLS}（静态资源、Swagger）不是本服务
	 * controller、不会被 {@code addPathPrefix} 改写，所以原样排除、不加前缀。
	 */
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new ThirdSessionInterceptor(this.redisTemplate))
				.excludePathPatterns(excludedPatterns(this.pathPrefix).toArray(String[]::new));
	}

	/**
	 * 排除清单 = 加了前缀的门户业务路径 + 原样的基础设施路径。
	 *
	 * <p>单独抽成静态方法是为了能直接断言 —— {@code InterceptorRegistry} 注册完之后取不回
	 * 排除清单，而这段逻辑错了的后果（四端登录的 captcha 被 60004 拒、回调落进默认租户上下文）
	 * **权限矩阵探不到**。
	 */
	static List<String> excludedPatterns(String pathPrefix) {
		List<String> excluded = new ArrayList<>(
				PathPrefixConstants.prefixed(pathPrefix, PublicUrlConstants.PORTAL_PUBLIC_URLS));
		excluded.addAll(PublicUrlConstants.COMMON_PUBLIC_URLS);
		return List.copyOf(excluded);
	}
}
