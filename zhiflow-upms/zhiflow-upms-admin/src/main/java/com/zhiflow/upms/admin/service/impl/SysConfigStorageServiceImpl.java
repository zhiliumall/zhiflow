/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.admin.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.data.tenant.TenantContextManager;
import com.zhiflow.upms.common.entity.SysConfigStorage;
import com.zhiflow.upms.admin.mapper.SysConfigStorageMapper;
import com.zhiflow.upms.admin.service.SysConfigStorageService;
import lombok.AllArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 存储配置
 *
 * @date 2020-02-03 20:07:38
 */
@Service
@AllArgsConstructor
public class SysConfigStorageServiceImpl extends ServiceImpl<SysConfigStorageMapper, SysConfigStorage> implements SysConfigStorageService {

	private final RedisTemplate redisTemplate;

	@Override
	@CacheEvict(value = CacheConstants.SYS_CONFIG_STORAGE, key = "'value'")
	public boolean updateById(SysConfigStorage entity) {
		return super.updateById(entity);
	}

	SysConfigStorage getOneCashe() {
		SysConfigStorage sysConfigStorage = null;
		String key = getRedisKey();
		//获取缓存配置
		if(redisTemplate.hasKey(key)) {
			Object obj = redisTemplate.opsForValue().get(key);
			if(obj != null){
				sysConfigStorage = JSONUtil.toBean(String.valueOf(obj), SysConfigStorage.class);
			}
		}
		//无缓存则查数据库
		if(sysConfigStorage == null){
			sysConfigStorage = super.getOne(Wrappers.emptyWrapper());
			if(sysConfigStorage != null){
				//存入缓存
				String data = JSONUtil.toJsonStr(sysConfigStorage);
				redisTemplate.opsForValue().set(key, data);
			}
		}
		return sysConfigStorage;
	}

	@Override
	public SysConfigStorage getOne() {
		SysConfigStorage sysConfigStorage = this.getOneCashe();
		if(sysConfigStorage != null
				&& CommonConstants.NO.equals(sysConfigStorage.getIsSelf())){
			//非自配服务，则取平台的配置
			sysConfigStorage = TenantContextManager.executeWithTenant(
					CommonConstants.SYSTEM_TENANT_ID,
					this::getOneCashe
			);
		}
		return sysConfigStorage;
	}

	String getRedisKey(){
		return StrUtil.format("{}:{}::{}", TenantContextHolder.getTenantId(), CacheConstants.SYS_CONFIG_STORAGE, "value");
	}
}
