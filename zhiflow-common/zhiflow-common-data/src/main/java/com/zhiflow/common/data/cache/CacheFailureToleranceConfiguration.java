package com.zhiflow.common.data.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.lang.Nullable;

/**
 * 缓存故障容错（2026-07-09 P1 稳定性整改）。
 * <p>
 * 默认行为下 Redis 不可用时 @Cacheable 直接抛异常，缓存类接口整片报错。
 * 注册 {@link CacheErrorHandler} 后：读失败按缓存 miss 处理（业务方法照常执行、直查 DB），
 * 写/清失败仅记日志（条目带 TTL，脏留存有界）。
 * <p>
 * 注意：降级后的每次缓存读仍要等 Redis 命令超时才回落 DB，故障期表现为「慢但可用」；
 * 若需快速失败需在上游加熔断（见审计报告 P2）。
 */
@AutoConfiguration
@ConditionalOnClass(RedisConnectionFactory.class)
public class CacheFailureToleranceConfiguration implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheFailureToleranceConfiguration.class);

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("[缓存降级] 读缓存失败按 miss 处理, cache={}, key={}, err={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, @Nullable Object value) {
                log.warn("[缓存降级] 写缓存失败已忽略, cache={}, key={}, err={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("[缓存降级] 缓存删除失败已忽略（条目有 TTL，脏留存有界）, cache={}, key={}, err={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("[缓存降级] 缓存清空失败已忽略, cache={}, err={}",
                        cache.getName(), exception.getMessage());
            }
        };
    }
}
