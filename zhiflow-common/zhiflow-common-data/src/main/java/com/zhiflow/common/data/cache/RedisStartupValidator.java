package com.zhiflow.common.data.cache;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class RedisStartupValidator implements ApplicationListener<ApplicationReadyEvent> {

    @Qualifier("redisTemplate")
    private final RedisTemplate<String, Object> redisTemplate;

    @Qualifier("oauth2RedisTemplate")
    private final RedisTemplate<String, Object> oauth2RedisTemplate;

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("=== 开始验证 RedisTemplate Bean 配置 ===");
        
        boolean allValid = true;
        
        allValid &= validateTemplate(redisTemplate, "redisTemplate");
        allValid &= validateTemplate(oauth2RedisTemplate, "oauth2RedisTemplate");
        allValid &= validateStringTemplate(stringRedisTemplate, "stringRedisTemplate");
        
        if (allValid) {
            log.info("=== 所有 RedisTemplate Bean 验证通过 ===");
        } else {
            log.error("=== RedisTemplate Bean 验证失败，请检查配置 ===");
        }
    }

    private boolean validateTemplate(RedisTemplate<String, Object> template, String beanName) {
        try {
            if (template == null) {
                log.error("{} - Bean 为 null", beanName);
                return false;
            }
            if (template.getConnectionFactory() == null) {
                log.error("{} - ConnectionFactory 未配置", beanName);
                return false;
            }
            if (template.getKeySerializer() == null) {
                log.error("{} - KeySerializer 未配置", beanName);
                return false;
            }
            if (template.getValueSerializer() == null) {
                log.error("{} - ValueSerializer 未配置", beanName);
                return false;
            }
            
            String testKey = "zhiflow:startup:test:" + beanName;
            template.opsForValue().set(testKey, "test");
            Object result = template.opsForValue().get(testKey);
            template.delete(testKey);
            
            if (result == null) {
                log.warn("{} - 读写测试失败", beanName);
                return false;
            }
            
            log.info("{} - 验证通过 (Serializer: {})", 
                beanName, template.getValueSerializer().getClass().getSimpleName());
            return true;
        } catch (Exception e) {
            log.error("{} - 验证异常: {}", beanName, e.getMessage());
            return false;
        }
    }

    private boolean validateStringTemplate(StringRedisTemplate template, String beanName) {
        try {
            if (template == null) {
                log.error("{} - Bean 为 null", beanName);
                return false;
            }
            
            String testKey = "zhiflow:startup:test:" + beanName;
            template.opsForValue().set(testKey, "test");
            String result = template.opsForValue().get(testKey);
            template.delete(testKey);
            
            if (!"test".equals(result)) {
                log.warn("{} - 读写测试失败", beanName);
                return false;
            }
            
            log.info("{} - 验证通过", beanName);
            return true;
        } catch (Exception e) {
            log.error("{} - 验证异常: {}", beanName, e.getMessage());
            return false;
        }
    }
}
