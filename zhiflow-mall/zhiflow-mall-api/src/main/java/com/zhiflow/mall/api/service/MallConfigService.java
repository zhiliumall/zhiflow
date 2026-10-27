/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.MallConfig;

import java.util.Map;

/**
 * 商城系统配置
 *
 * @date 2025-07-22
 */
public interface MallConfigService extends IService<MallConfig> {
    
    /**
     * 获取所有配置项（带缓存）
     * @return 配置项Map
     */
    Map<String, Object> getAllConfigs();
    
    /**
     * 获取单个配置值
     * @param key 配置键
     * @param type 返回类型
     * @param defaultValue 默认值
     * @return 配置值
     */
    <T> T getConfig(String key, Class<T> type, T defaultValue);
    
    /**
     * 获取指定租户的配置
     * @param tenantId 租户ID
     * @return 配置项Map
     */
    Map<String, Object> getConfigsByTenantId(String tenantId);
    
    /**
     * 获取公开配置（过滤敏感信息）
     * @return 公开配置Map
     */
    Map<String, Object> getPublicConfigs();
}