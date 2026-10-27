/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.api.mapper.MallConfigMapper;
import com.zhiflow.mall.api.service.MallConfigService;
import com.zhiflow.mall.common.entity.MallConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    /**
     * 本地缓存 TTL 硬编码 5 秒上限（不经配置放大），
     * 对应设计里「后台改配置的生效延迟 ≤ 5 秒」的已接受风险——不额外加可配置的 TTL 旋钮。
     */
    private static final long LOCAL_CACHE_TTL_SECONDS = 5L;

    /**
     * L0：进程内缓存，键 = tenantId。命中免去 Redis 往返 + 反序列化。
     * <p>perf 2026-10-18（剖析发现空结果租户每次 getConfig 都查库，
     * 约占每单 8% CPU）加入；2026-10-23 删开关，恒开（「代码默认关」两次让新环境静默变慢）。
     * <p>静态字段：一是避免和 Lombok {@code @AllArgsConstructor} 的构造参数产生歧义
     * （实例字段若带初始化表达式，会被排除在生成的构造函数之外，容易把"没有注入"和"本来就没有这个依赖"搞混，
     * 这里直接按静态字段处理，语义更明确）；二是本类本身是单例 bean，静态和实例在行为上等价。
     * <p>只存不可变视图（见 {@link Collections#unmodifiableMap}）：调用方目前只读
     * （{@link #getConfig}/{@link #getPublicConfigs} 只做 {@code get}/遍历，不改 Map 本身），
     * 万一哪天改成会写，这里会直接抛 {@code UnsupportedOperationException}，
     * 而不是悄悄污染同一 TTL 窗口内其它调用方看到的缓存值。
     */
    private static final Cache<String, Map<String, Object>> LOCAL_CACHE = Caffeine.newBuilder()
            .expireAfterWrite(LOCAL_CACHE_TTL_SECONDS, TimeUnit.SECONDS)
            .maximumSize(1024)
            .build();

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
        // perf 2026-10-18（集成复审第 1 条）：Caffeine 的 Cache#getIfPresent/#put 不接受 null 键，直接抛 NPE。
        // 没带 token 也没带 tenant-id 头的请求（TenantContextHolderFilter 这种情况不设租户，getTenantId() 返回
        // null）打到公开配置接口（MallSystemConfigController 的 public 接口）时 tenantId 就是 null；
        // 这里用 tenantId 是否为空白先短路，空租户号时两处都不碰 LOCAL_CACHE，只读 Redis/DB
        // （cacheKey 字面量仍是 "mall:config:null"，与加本地缓存前完全一致），不让一个 NPE 把
        // 原本能正常返回的公开接口打成 500。
        boolean tenantKeyable = StrUtil.isNotBlank(tenantId);

        if (tenantKeyable) {
            Map<String, Object> cached = LOCAL_CACHE.getIfPresent(tenantId);
            if (cached != null) {
                return cached;
            }
        }

        Map<String, Object> configs = loadFromRedisOrDb(tenantId);

        if (tenantKeyable) {
            LOCAL_CACHE.put(tenantId, Collections.unmodifiableMap(configs));
        }

        return configs;
    }

    /**
     * Redis（L1，1 小时 TTL）未命中则查库。
     * <p>perf 2026-10-18：空结果也写 Redis（此前只有非空才写：租户在 {@code mall_config} 里 0 行时，
     * 每次 {@code getConfig} 都要查一次库，约占每单 8% CPU）。
     * <p>所有写配置的路径最终都会调 mall-admin {@code MallConfigServiceImpl#clearCache()}
     * （{@code MallSystemConfigController} 的 save/updateById/updateConfigs/removeById、
     * {@code upsertConfig}、{@code HkdCnyRateSyncTask}），删的就是这同一个 {@code mall:config:{tenantId}}
     * key（tenantId 取自写入方当时的 {@code TenantContextHolder}，与这里读时用的 tenantId 同源），
     * 所以空结果缓存不会导致"后台新增配置后 mall-api 一小时看不到"：删键之后下一次读落到未命中分支，
     * 查库拿到的就是最新数据（另有进程内 {@link #LOCAL_CACHE} 最多 5 秒的延迟）。
     */
    private Map<String, Object> loadFromRedisOrDb(String tenantId) {
        String cacheKey = CACHE_KEY_PREFIX + tenantId;

        // 从缓存获取
        @SuppressWarnings("unchecked")
        Map<String, Object> configs = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);
        if (configs != null) {
            return configs;
        }

        // 从数据库加载
        configs = loadConfigsFromDb(tenantId);

        // 存入缓存（含空结果）
        redisTemplate.opsForValue().set(cacheKey, configs, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);

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
}