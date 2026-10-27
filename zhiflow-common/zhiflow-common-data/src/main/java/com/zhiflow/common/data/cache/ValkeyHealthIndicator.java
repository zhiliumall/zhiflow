package com.zhiflow.common.data.cache;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Slf4j
@Component("valkey")
@AllArgsConstructor
public class ValkeyHealthIndicator implements HealthIndicator {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisConnectionFactory redisConnectionFactory;

    @Override
    public Health health() {
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) connection -> {
                return connection.ping();
            });
            
            if ("PONG".equals(pong)) {
                Properties info = redisTemplate.execute((RedisCallback<Properties>) connection -> {
                    return connection.info("server");
                });
                
                Health.Builder builder = Health.up()
                    .withDetail("ping", "PONG")
                    .withDetail("type", "Valkey/Redis");
                
                if (info != null) {
                    String version = info.getProperty("redis_version");
                    if (version != null) {
                        builder.withDetail("version", version);
                    }
                }
                
                return builder.build();
            } else {
                return Health.down()
                    .withDetail("ping", pong)
                    .withDetail("error", "Unexpected ping response")
                    .build();
            }
        } catch (Exception e) {
            log.error("Valkey health check failed", e);
            return Health.down(e)
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
