/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.storage.local;

import com.zhiflow.common.storage.util.InlineUnsafeTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * 本地存储：存文件的 {@link LocalFileStorage}，以及把上传目录映射成 {@code {urlPrefix}/**} 静态资源。
 * 匿名可读要在安全配置的放行地址里加上同一个前缀（单体 application.yml 的 release-urls）。
 *
 * <p>返回头：文件名是随机 UUID、内容不再变，缓存 30 天；所有文件带 {@code Content-Security-Policy: sandbox}，
 * {@link InlineUnsafeTypes} 里的类型另加 {@code Content-Disposition: attachment}——应用和上传文件同域，
 * 一个能在浏览器里跑脚本的 html / svg 就能读到后台登录态。
 */
@AutoConfiguration
@EnableConfigurationProperties(LocalStorageProperties.class)
public class LocalStorageAutoConfiguration {

	@Bean
	public LocalFileStorage localFileStorage(LocalStorageProperties properties) {
		return new LocalFileStorage(properties);
	}

	@Bean
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	public WebMvcConfigurer localStorageResourceHandler(LocalStorageProperties properties, LocalFileStorage storage) {
		String prefix = properties.getUrlPrefix();
		// url-prefix 写成完整地址（前后端不同域）时，应用自己仍按它的路径部分提供文件
		String path = prefix.replaceFirst("^https?://[^/]+", "");
		String pattern = (path.endsWith("/") ? path : path + "/") + "**";
		String location = storage.root().toUri().toString();
		return new WebMvcConfigurer() {
			@Override
			public void addResourceHandlers(ResourceHandlerRegistry registry) {
				registry.addResourceHandler(pattern)
					.addResourceLocations(location.endsWith("/") ? location : location + "/")
					.setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
			}

			@Override
			public void addInterceptors(InterceptorRegistry registry) {
				registry.addInterceptor(new HandlerInterceptor() {
					@Override
					public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
						response.setHeader("Content-Security-Policy", "sandbox");
						if (InlineUnsafeTypes.isUnsafe(request.getRequestURI())) {
							response.setHeader("Content-Disposition", "attachment");
						}
						return true;
					}
				}).addPathPatterns(pattern);
			}
		};
	}
}
