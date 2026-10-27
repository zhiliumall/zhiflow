/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.weixin.admin.config.mp;

import java.util.concurrent.TimeUnit;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import me.chanjar.weixin.common.enums.TicketType;
import me.chanjar.weixin.mp.config.impl.WxMpDefaultConfigImpl;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 基于Redis的微信配置provider.
 * 
 */
@SuppressWarnings("serial")
public class WxMpInRedisConfigStorage extends WxMpDefaultConfigImpl {

	public static final String ACCESS_TOKEN_KEY = StrUtil.format("{}:{}:{}", TenantContextHolder.getTenantId(), CacheConstants.WXMP_CACHE, ":access_token:");

	public final static String JSAPI_TICKET_KEY = StrUtil.format("{}:{}:{}", TenantContextHolder.getTenantId(), CacheConstants.WXMP_CACHE, ":jsapi_ticket:");

	public final static String CARDAPI_TICKET_KEY = StrUtil.format("{}:{}:{}", TenantContextHolder.getTenantId(), CacheConstants.WXMP_CACHE, ":cardapi_ticket:");

	private final RedisTemplate<String, String> redisTemplate;

	public WxMpInRedisConfigStorage(RedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	private String accessTokenKey;

	private String jsapiTicketKey;

	private String cardapiTicketKey;

	/**
	 * 每个公众号生成独有的存储key.
	 */
	@Override
	public void setAppId(String appId) {
		super.setAppId(appId);
		this.accessTokenKey = ACCESS_TOKEN_KEY.concat(appId);
		this.jsapiTicketKey = JSAPI_TICKET_KEY.concat(appId);
		this.cardapiTicketKey = CARDAPI_TICKET_KEY.concat(appId);
	}

	/**
	 *
	 * @param type
	 * @return
	 */
	private String getTicketRedisKey(TicketType type) {
		return StrUtil.format("{}:{}:ticket:key:{}:{}", TenantContextHolder.getTenantId(), CacheConstants.WXMP_CACHE, this.appId, type.getCode());
	}

	/**
	 *
	 * @return
	 */
	@Override
	public String getAccessToken() {
		return redisTemplate.opsForValue().get(this.accessTokenKey);
	}

	/**
	 *
	 * @return
	 */
	@Override
	public boolean isAccessTokenExpired() {
		return redisTemplate.getExpire(accessTokenKey) < 2;
	}

	/**
	 *
	 * @param accessToken
	 * @param expiresInSeconds
	 */
	@Override
	public synchronized void updateAccessToken(String accessToken, int expiresInSeconds) {
		redisTemplate.opsForValue().set(this.accessTokenKey, accessToken, expiresInSeconds - 200, TimeUnit.SECONDS);
	}

	/**
	 *
	 */
	@Override
	public void expireAccessToken() {
		redisTemplate.expire(this.accessTokenKey, 0, TimeUnit.SECONDS);
	}

	/**
	 *
	 * @param type
	 * @return
	 */
	@Override
	public String getTicket(TicketType type) {
		return redisTemplate.opsForValue().get(this.getTicketRedisKey(type));
	}

	/**
	 *
	 * @param type
	 * @return
	 */
	@Override
	public boolean isTicketExpired(TicketType type) {
		return redisTemplate.getExpire(this.getTicketRedisKey(type)) < 2;
	}

	/**
	 *
	 * @param type
	 * @param jsapiTicket
	 * @param expiresInSeconds
	 */
	@Override
	public synchronized void updateTicket(TicketType type, String jsapiTicket, int expiresInSeconds) {
		redisTemplate.opsForValue().set(this.getTicketRedisKey(type), jsapiTicket, expiresInSeconds - 200, TimeUnit.SECONDS);
	}

	/**
	 *
	 * @param type
	 */
	@Override
	public void expireTicket(TicketType type) {
		redisTemplate.expire(this.getTicketRedisKey(type), 0, TimeUnit.SECONDS);
	}

	/**
	 *
	 * @return
	 */
	@Override
	public String getJsapiTicket() {
		return redisTemplate.opsForValue().get(this.jsapiTicketKey);
	}

	/**
	 *
	 * @return
	 */
	@Override
	public String getCardApiTicket() {
		return redisTemplate.opsForValue().get(cardapiTicketKey);
	}
}
