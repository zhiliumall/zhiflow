package com.zhiflow.common.data.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * 多级缓存失效订阅配置
 *
 * 功能：
 *  - 同一 topic 的多节点订阅 {@link MultiLevelCacheService#INVALIDATE_CHANNEL}
 *  - 收到消息时调用 {@link MultiLevelCacheService#invalidateLocalOnly(String)} 清自身 L1
 *  - 发送方已在 evict 时清了自身 L1 + L2 + publish，不会因监听再次失效导致死循环
 *
 * 序列化对齐：
 *  - publish 端使用 RedisTemplate 的 valueSerializer（GenericJacksonJsonRedisSerializer）
 *  - 这里反序列化 body 拿原始 key 字符串
 */
@Slf4j
@Configuration
// [S1 2026-08-16] 原先是 @ConditionalOnBean({RedisConnectionFactory, MultiLevelCacheService})，
// 而本类在 com.zhiflow.common.data.cache 下、是被**组件扫描**进来的 —— @ConditionalOnBean
// 在组件扫描阶段求值，那时它依赖的两个 bean 还没注册完，条件必然不成立 ⇒ **这个订阅者从来没被创建过**。
// 实测判据：`PUBSUB NUMSUB cache:invalidate` = 0、启动日志 0 条「多级缓存失效订阅已启动」。
// 后果：跨节点 L1 失效广播是死的 —— ×1 时表现为「陈旧最长 5 分钟」，×2 会变成「两个实例来回抖」。
//
// 改成显式开关：本类与 MultiLevelCacheService 同包同扫描面，依赖必然在，不需要条件推断。
// 开关留给**冒烟门禁**用：RedisMessageListenerContainer 是 SmartLifecycle，start() 时**真的连**
// Redis，而冒烟的纪律是「只挡外部网络连接」—— 那里置 false，不是因为这个功能可选。
@ConditionalOnProperty(name = "zhiflow.cache.invalidate-listener.enabled", matchIfMissing = true)
public class CacheInvalidateListenerConfig {

    @Bean
    public RedisMessageListenerContainer cacheInvalidateContainer(
            RedisConnectionFactory connectionFactory,
            MultiLevelCacheService cache,
            RedisTemplate<String, Object> redisTemplate) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        @SuppressWarnings("unchecked")
        RedisSerializer<Object> serializer = (RedisSerializer<Object>) redisTemplate.getValueSerializer();
        container.addMessageListener((message, pattern) -> {
            try {
                Object decoded = serializer.deserialize(message.getBody());
                if (decoded instanceof String key) {
                    cache.invalidateLocalOnly(key);
                    log.debug("收到缓存失效广播，清 L1: {}", key);
                }
            } catch (Exception e) {
                log.warn("处理缓存失效消息失败", e);
            }
        }, new ChannelTopic(MultiLevelCacheService.INVALIDATE_CHANNEL));
        log.info("多级缓存失效订阅已启动 channel={}", MultiLevelCacheService.INVALIDATE_CHANNEL);
        return container;
    }
}

