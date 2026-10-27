/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.pay.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.common.core.constant.CommonConstants;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.pay.common.entity.PayConfig;
import com.zhiflow.pay.admin.mapper.PayConfigMapper;
import com.zhiflow.pay.admin.service.PayConfigService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.io.Serializable;

/**
 * 支付配置
 *
 * @date 2020-05-29 16:37:13
 */
@Service
public class PayConfigServiceImpl extends ServiceImpl<PayConfigMapper, PayConfig> implements PayConfigService {

	@Override
	@Cacheable(value = CacheConstants.PAY_CONFIC_APP_ID_CACHE, key = "#appId", unless = "#result == null")
	public PayConfig findByAppId(String appId) {
		return baseMapper.selectOne(Wrappers.<PayConfig>lambdaQuery()
				.eq(PayConfig::getAppId, appId)
				.eq(PayConfig::getDelFlag, CommonConstants.STATUS_NORMAL));
	}

	@Override
	@Cacheable(value = CacheConstants.PAY_CONFIC_TYPE_CACHE, key = "#type", unless = "#result == null")
	public PayConfig findByType(String type) {
		return baseMapper.selectOne(Wrappers.<PayConfig>lambdaQuery()
				.eq(PayConfig::getType, type)
				.eq(PayConfig::getDelFlag, CommonConstants.STATUS_NORMAL)
				.last("limit 1"));
	}

	@Override
	@Cacheable(value = CacheConstants.PAY_CONFIC_APP_ID_TYPE_CACHE, key = "#appId + ':' + #type", unless = "#result == null")
	public PayConfig findByAppIdAndType(String appId, String type) {
		return baseMapper.selectOne(Wrappers.<PayConfig>lambdaQuery()
				.eq(PayConfig::getAppId, appId)
				.eq(PayConfig::getType, type)
				.eq(PayConfig::getDelFlag, CommonConstants.STATUS_NORMAL));
	}

	@Override
	@Caching(evict={
			@CacheEvict(value = CacheConstants.PAY_CONFIC_TYPE_CACHE, allEntries = true),
			@CacheEvict(value = CacheConstants.PAY_CONFIC_APP_ID_CACHE, allEntries = true),
			// findByAppIdAndType 的缓存也存 pay_config 行，改配置时实体里的旧 appId 未知，只能 allEntries 清
			@CacheEvict(value = CacheConstants.PAY_CONFIC_APP_ID_TYPE_CACHE, allEntries = true)
	})
	public boolean updateById(PayConfig entity) {
		baseMapper.updateById(entity);
		return Boolean.TRUE;
	}

	@Override
	@Caching(evict={
			@CacheEvict(value = CacheConstants.PAY_CONFIC_TYPE_CACHE, allEntries = true),
			@CacheEvict(value = CacheConstants.PAY_CONFIC_APP_ID_CACHE, allEntries = true),
			@CacheEvict(value = CacheConstants.PAY_CONFIC_APP_ID_TYPE_CACHE, allEntries = true)
	})
	public boolean removeById(Serializable id) {
		baseMapper.deleteById(id);
		return Boolean.TRUE;
	}
}
