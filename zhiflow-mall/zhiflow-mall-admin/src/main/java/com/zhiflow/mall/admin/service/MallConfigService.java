/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service;

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
    
    /**
     * 清除配置缓存
     */
    void clearCache();
    
    /**
     * 批量更新配置
     * @param configs 配置Map
     */
    void updateConfigs(Map<String, String> configs);

    /**
     * 当前租户写入（不存在则新增）一个配置项，并清缓存。
     * 与 {@link #updateConfigs} 的区别：新增时写入给定的类型与描述，而不是一律 string / 无描述。
     */
    void upsertConfig(String key, String value, String type, String desc);
}