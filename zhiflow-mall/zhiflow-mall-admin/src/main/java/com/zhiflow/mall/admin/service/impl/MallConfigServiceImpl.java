/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.admin.mapper.MallConfigMapper;
import com.zhiflow.mall.admin.service.MallConfigService;
import com.zhiflow.mall.common.entity.MallConfig;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商城系统配置
 *
 * @date 2025-07-22
 */
@Slf4j
@Service
@AllArgsConstructor
public class MallConfigServiceImpl extends ServiceImpl<MallConfigMapper, MallConfig> implements MallConfigService {
    
    private final RedisTemplate<String, Object> redisTemplate;
    
    private static final String CACHE_KEY_PREFIX = "mall:config:";
    private static final long CACHE_EXPIRE_HOURS = 1;
    
    // 公开配置键前缀列表（这些配置可以公开给前端）
    private static final String[] PUBLIC_CONFIG_PREFIXES = {
        "prescription.",
        "order.",
        "user.",
        "display.",
        "feature."
    };
    
    @Override
    public Map<String, Object> getAllConfigs() {
        String tenantId = TenantContextHolder.getTenantId();
        return getConfigsByTenantId(tenantId);
    }
    
    @Override
    public Map<String, Object> getConfigsByTenantId(String tenantId) {
        String cacheKey = CACHE_KEY_PREFIX + tenantId;
        
        // 从缓存获取
        Map<String, Object> configs = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);
        if (configs != null) {
            return configs;
        }
        
        // 从数据库加载
        configs = loadConfigsFromDb(tenantId);
        
        // 存入缓存
        if (!configs.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, configs, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        }
        
        return configs;
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String key, Class<T> type, T defaultValue) {
        Map<String, Object> configs = getAllConfigs();
        Object value = configs.get(key);
        
        if (value == null) {
            return defaultValue;
        }
        
        // 类型转换
        try {
            if (type == String.class) {
                return (T) value.toString();
            } else if (type == Integer.class) {
                return (T) Integer.valueOf(value.toString());
            } else if (type == Long.class) {
                return (T) Long.valueOf(value.toString());
            } else if (type == Boolean.class) {
                return (T) Boolean.valueOf(value.toString());
            } else if (type == Double.class) {
                return (T) Double.valueOf(value.toString());
            } else {
                return (T) value;
            }
        } catch (Exception e) {
            log.error("配置类型转换失败，key: {}, value: {}, type: {}", key, value, type, e);
            return defaultValue;
        }
    }
    
    @Override
    public Map<String, Object> getPublicConfigs() {
        Map<String, Object> allConfigs = getAllConfigs();
        Map<String, Object> publicConfigs = new HashMap<>();
        
        // 过滤出公开配置
        for (Map.Entry<String, Object> entry : allConfigs.entrySet()) {
            String key = entry.getKey();
            // 检查是否是公开配置
            for (String prefix : PUBLIC_CONFIG_PREFIXES) {
                if (key.startsWith(prefix)) {
                    publicConfigs.put(key, entry.getValue());
                    break;
                }
            }
        }
        
        return publicConfigs;
    }
    
    /**
     * 从数据库加载配置
     */
    private Map<String, Object> loadConfigsFromDb(String tenantId) {
        List<MallConfig> configList = this.list(Wrappers.<MallConfig>lambdaQuery()
                .eq(MallConfig::getTenantId, tenantId)
                .eq(MallConfig::getDelFlag, CommonConstants.STATUS_NORMAL));
        
        Map<String, Object> configs = new HashMap<>();
        for (MallConfig config : configList) {
            if (StrUtil.isNotBlank(config.getConfigKey()) && config.getConfigValue() != null) {
                // 根据类型转换值
                Object value = convertValue(config.getConfigValue(), config.getConfigType());
                configs.put(config.getConfigKey(), value);
            }
        }
        
        return configs;
    }
    
    /**
     * 根据类型转换配置值
     */
    private Object convertValue(String value, String type) {
        if (StrUtil.isBlank(type)) {
            return value;
        }
        
        try {
            switch (type.toLowerCase()) {
                case "number":
                case "integer":
                    return Integer.valueOf(value);
                case "long":
                    return Long.valueOf(value);
                case "boolean":
                    return Boolean.valueOf(value);
                case "double":
                    return Double.valueOf(value);
                case "json":
                    // 这里可以使用 JSON 工具类进行转换
                    return value;
                default:
                    return value;
            }
        } catch (Exception e) {
            log.error("配置值类型转换失败，value: {}, type: {}", value, type, e);
            return value;
        }
    }
    
    @Override
    public void clearCache() {
        String tenantId = TenantContextHolder.getTenantId();
        String cacheKey = CACHE_KEY_PREFIX + tenantId;
        redisTemplate.delete(cacheKey);
    }
    
    /**
     * S6：<b>补 {@code @Transactional}。</b>
     *
     * <p>原实现无事务，循环逐条 getOne + update/insert。中途任一条抛异常时，
     * 前几条配置<b>已经落库</b>、后几条没有——商城配置处于半更新状态，
     * 而调用方拿到的是一个 500，根本不知道哪些生效了哪些没有。
     * 配置项之间往往有关联（如「开启某功能」+「该功能的参数」），半生效比全不生效更危险。
     *
     * <p>{@code rollbackFor = Exception.class} 不可省：Spring 默认只回滚 RuntimeException。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfigs(Map<String, String> configs) {
        String tenantId = TenantContextHolder.getTenantId();
        
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            
            // 查找是否已存在该配置
            MallConfig config = this.getOne(Wrappers.<MallConfig>lambdaQuery()
                    .eq(MallConfig::getTenantId, tenantId)
                    .eq(MallConfig::getConfigKey, key)
                    .eq(MallConfig::getDelFlag, CommonConstants.STATUS_NORMAL));
            
            if (config != null) {
                // 更新现有配置
                config.setConfigValue(value);
                this.updateById(config);
            } else {
                // 创建新配置
                config = new MallConfig();
                config.setTenantId(tenantId);
                config.setConfigKey(key);
                config.setConfigValue(value);
                config.setConfigType("string");
                config.setDelFlag(CommonConstants.STATUS_NORMAL);
                this.save(config);
            }
        }
        
        // 清除缓存
        clearCache();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void upsertConfig(String key, String value, String type, String desc) {
        String tenantId = TenantContextHolder.getTenantId();
        MallConfig config = this.getOne(Wrappers.<MallConfig>lambdaQuery()
                .eq(MallConfig::getTenantId, tenantId)
                .eq(MallConfig::getConfigKey, key)
                .eq(MallConfig::getDelFlag, CommonConstants.STATUS_NORMAL));
        if (config != null) {
            config.setConfigValue(value);
            config.setConfigType(type);
            if (StrUtil.isBlank(config.getConfigDesc())) {
                config.setConfigDesc(desc);
            }
            this.updateById(config);
        } else {
            config = new MallConfig();
            config.setTenantId(tenantId);
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setConfigType(type);
            config.setConfigDesc(desc);
            config.setDelFlag(CommonConstants.STATUS_NORMAL);
            this.save(config);
        }
        clearCache();
    }
}