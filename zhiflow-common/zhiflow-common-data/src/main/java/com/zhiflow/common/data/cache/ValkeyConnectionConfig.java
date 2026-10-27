package com.zhiflow.common.data.cache;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

/**
 * Valkey 连接配置
 * 
 * Valkey 是 Redis 的开源分支，需要特殊配置：
 * 1. 使用 RESP3 协议（Valkey 推荐）
 * 2. 优化连接池和超时设置
 * 3. 启用自动重连
 */
@Slf4j
@AutoConfiguration(before = DataRedisAutoConfiguration.class)
public class ValkeyConnectionConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory(DataRedisProperties redisProperties) {
        log.info("=== 初始化 Valkey 连接工厂 ===");
        log.info("Host: {}, Port: {}, Database: {}", 
            redisProperties.getHost(), 
            redisProperties.getPort(),
            redisProperties.getDatabase());

        // Valkey 服务器配置
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(redisProperties.getHost());
        serverConfig.setPort(redisProperties.getPort());
        serverConfig.setDatabase(redisProperties.getDatabase());
        
        if (redisProperties.getPassword() != null && 
            !redisProperties.getPassword().isEmpty()) {
            serverConfig.setPassword(redisProperties.getPassword());
            log.info("Valkey 密码认证已启用");
        }

        // Lettuce 客户端配置（针对 Valkey 优化）
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .clientOptions(buildClientOptions(redisProperties))
            .commandTimeout(redisProperties.getTimeout() != null ? redisProperties.getTimeout() : Duration.ofSeconds(60))
            .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(
            serverConfig, clientConfig);
        
        // 关键：必须初始化
        factory.afterPropertiesSet();
        
        log.info("=== Valkey 连接工厂初始化成功 ===");
        return factory;
    }

    private ClientOptions buildClientOptions(DataRedisProperties properties) {
        ClientOptions.Builder builder = ClientOptions.builder();

        // 1. RESP3 协议（Valkey 推荐）
        try {
            builder.protocolVersion(ProtocolVersion.RESP3);
            log.info("Valkey RESP3 协议已启用");
        } catch (Exception e) {
            log.warn("Valkey RESP3 不可用，使用默认协议", e);
        }

        Duration timeout = properties.getTimeout() != null ? properties.getTimeout() : Duration.ofSeconds(60);

        // 2. 超时配置
        builder.timeoutOptions(TimeoutOptions.enabled(timeout));

        // 3. Socket 配置
        builder.socketOptions(SocketOptions.builder()
            .connectTimeout(timeout)
            .keepAlive(true)
            .tcpNoDelay(true)
            .build());

        // 4. 自动重连
        builder.autoReconnect(true);

        // 5. 连接失败后的行为
        builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);

        // 6. 请求队列大小
        builder.requestQueueSize(256);

        log.info("Valkey ClientOptions 配置完成");
        return builder.build();
    }
}
