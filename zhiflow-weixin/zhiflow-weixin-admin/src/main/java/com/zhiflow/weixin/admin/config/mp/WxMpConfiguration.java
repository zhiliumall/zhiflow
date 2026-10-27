/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.weixin.admin.config.mp;

import com.zhiflow.weixin.admin.security.WxAppTenantScopeGuard;
import com.zhiflow.weixin.admin.service.WxAppService;
import com.zhiflow.weixin.common.entity.WxApp;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.impl.WxMpServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公众号 {@link WxMpService} 工厂：按 appId 从 wx_app 取 appid / secret 构建并缓存。
 *
 * <p>开源版只用公众号的网页授权与 openid（D-7a），不接收公众号消息，也不接开放平台托管的公众号。
 */
@Slf4j
@Configuration
public class WxMpConfiguration {

	private static RedisTemplate redisTemplate;
	private static WxAppService wxAppService;
	private static WxAppTenantScopeGuard tenantScopeGuard;

	private static final Map<String, WxMpService> SERVICE_CACHE = new ConcurrentHashMap<>();

	@Autowired
	public WxMpConfiguration(RedisTemplate redisTemplate, WxAppService wxAppService,
			WxAppTenantScopeGuard tenantScopeGuard) {
		WxMpConfiguration.redisTemplate = redisTemplate;
		WxMpConfiguration.wxAppService = wxAppService;
		WxMpConfiguration.tenantScopeGuard = tenantScopeGuard;
	}

	public static WxMpService getMpService(String appId) {
		// 后台请求先核 appId 归属当前租户（H6），缓存命中也要核：缓存按 appId 跨租户共享
		if (tenantScopeGuard != null) {
			tenantScopeGuard.check(appId);
		}
		// 按 appId 缓存复用，首次构建后不再重复 new。
		// wx_app 配置（secret）变更后需 SERVICE_CACHE.remove(appId)，否则缓存的 service 持有旧配置。
		return SERVICE_CACHE.computeIfAbsent(appId, WxMpConfiguration::buildMpService);
	}

	private static WxMpService buildMpService(String appId) {
		WxApp wxApp = wxAppService.findByAppId(appId);
		if (wxApp == null) {
			throw new RuntimeException("无此appID:" + appId);
		}
		WxMpInRedisConfigStorage configStorage = new WxMpInRedisConfigStorage(redisTemplate);
		configStorage.setAppId(wxApp.getId());
		configStorage.setSecret(wxApp.getSecret());
		configStorage.setToken(wxApp.getToken());
		configStorage.setAesKey(wxApp.getAesKey());
		WxMpService wxMpService = new WxMpServiceImpl();
		wxMpService.setWxMpConfigStorage(configStorage);
		return wxMpService;
	}
}
