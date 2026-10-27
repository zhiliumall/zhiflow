/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.portal.util;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.Resource;
import java.time.Instant;

/**
 * 在线用户统计工具类
 * 参考：https://www.jb51.net/article/210809.htm
 * @date 2022-06-02 14:20:02
 */
@Component
public class UserOnlineUtil {
	private static final String ONLINE_USERS = "online_users";

	@Resource
	private RedisTemplate redisTemplate;

	/**
	 * 添加用户在线信息
	 * @param clientType 用户端
	 * @param userId
	 * @return
	 */
	public Boolean online(String clientType, String userId) {
		String key = StrUtil.format("{}:{}:{}", TenantContextHolder.getTenantId(), ONLINE_USERS, clientType);
		return this.redisTemplate.opsForZSet().add(key, userId, Instant.now().toEpochMilli());
	}

}
