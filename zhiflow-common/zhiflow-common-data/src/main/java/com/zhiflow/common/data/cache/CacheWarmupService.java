package com.zhiflow.common.data.cache;

import com.zhiflow.common.data.tenant.TenantAwareAsync;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;

/**
 * 缓存预热服务
 * 
 * 在应用启动时预热热点数据到 Valkey 缓存中
 * 
 * @date 2025-01-XX
 */
@Slf4j
@Component
public class CacheWarmupService {

	@Autowired(required = false)
	private RedisTemplate<String, Object> redisTemplate;

	/**
	 * 应用启动后预热缓存
	 */
	@PostConstruct
	public void warmupCache() {
		if (redisTemplate == null) {
			log.warn("RedisTemplate 未配置，跳过缓存预热");
			return;
		}

		log.info("=== 开始缓存预热 ===");
		
		// 异步预热，不阻塞应用启动
		TenantAwareAsync.runAsync(() -> {
			try {
				// 预热商品分类树缓存（由业务层通过 @Cacheable 自动处理）
				// 这里可以添加其他需要预热的缓存逻辑
				log.info("缓存预热完成");
			} catch (Exception e) {
				log.error("缓存预热失败", e);
			}
		});
	}

	/**
	 * 预热字典数据缓存
	 */
	public void warmupDict(String type, Object dictData) {
		if (redisTemplate == null || dictData == null) {
			return;
		}
		
		try {
			String key = "upms:dict:type:" + type;
			redisTemplate.opsForValue().set(key, dictData, Duration.ofMinutes(10));
			log.info("字典数据缓存预热成功: {}", type);
		} catch (Exception e) {
			log.error("字典数据缓存预热失败: {}", type, e);
		}
	}
}
