package com.zhiflow.common.data.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 多级缓存服务
 * 
 * 实现 L1（应用内存缓存 Caffeine）+ L2（Valkey 缓存）的多级缓存架构
 * 
 * 缓存流程：
 * 1. 检查应用内存缓存（Caffeine）→ 命中返回
 * 2. 检查 Valkey 缓存 → 命中返回并回写应用缓存
 * 3. 查询数据库 → 写入 Valkey 和应用缓存
 * 
 * @date 2025-01-XX
 */
@Slf4j
@Service
public class MultiLevelCacheService {

	@Autowired(required = false)
	private RedisTemplate<String, Object> redisTemplate;

	/**
	 * 应用内存缓存（L1 缓存）
	 * 使用 Caffeine 实现高性能本地缓存
	 */
	private final Cache<String, Object> localCache = Caffeine.newBuilder()
		.maximumSize(10_000)
		.expireAfterWrite(5, java.util.concurrent.TimeUnit.MINUTES)
		.recordStats()
		.build();

	/**
	 * 多级缓存获取
	 *
	 * @param key 缓存键
	 * @param type 值类型
	 * @param loader 数据加载器（当缓存未命中时调用）
	 * @param <T> 值类型
	 * @return 缓存值
	 */
	public <T> T get(String key, Class<T> type, Supplier<T> loader) {
		return get(key, type, loader, Duration.ofMinutes(10));
	}

	/**
	 * 多级缓存获取（自定义 L2 TTL）
	 * 用于活动信息等需要按业务时间窗动态过期的场景，如 min(5min, 距活动结束)。
	 *
	 * @param key 缓存键
	 * @param type 值类型
	 * @param loader 数据加载器（当缓存未命中时调用）
	 * @param l2Ttl L2 缓存过期时间（仅写入 Redis 时使用）
	 * @param <T> 值类型
	 * @return 缓存值
	 */
	public <T> T get(String key, Class<T> type, Supplier<T> loader, Duration l2Ttl) {
		// L1: 应用内存缓存
		Object value = localCache.getIfPresent(key);
		if (value != null) {
			log.debug("L1 缓存命中: {}", key);
			return type.cast(value);
		}

		// L2: Valkey 缓存
		if (redisTemplate != null) {
			value = redisTemplate.opsForValue().get(key);
			if (value != null) {
				log.debug("L2 缓存命中: {}", key);
				// 回写应用缓存
				localCache.put(key, value);
				return type.cast(value);
			}
		}

		// L3: 数据库查询
		log.debug("缓存未命中，从数据源加载: {}", key);
		T result = loader.get();
		if (result != null) {
			// 写入两级缓存
			localCache.put(key, result);
			if (redisTemplate != null && l2Ttl != null && !l2Ttl.isZero() && !l2Ttl.isNegative()) {
				redisTemplate.opsForValue().set(key, result, l2Ttl);
			}
		}

		return result;
	}

	/**
	 * 直接写入两级缓存（批量场景 loader 外部已拼好，绕开 get 的 loader 回调）。
	 * 允许 value=null/empty，作为防穿透占位。
	 */
	public void put(String key, Object value, Duration l2Ttl) {
		if (value == null) return;
		localCache.put(key, value);
		if (redisTemplate != null && l2Ttl != null && !l2Ttl.isZero() && !l2Ttl.isNegative()) {
			redisTemplate.opsForValue().set(key, value, l2Ttl);
		}
	}

	/**
	 * 清除缓存（多级）
	 *
	 * @param key 缓存键
	 */
	public void evict(String key) {
		localCache.invalidate(key);
		if (redisTemplate != null) {
			redisTemplate.delete(key);
			// 广播给集群中其他节点，让它们清自己的 L1
			try {
				redisTemplate.convertAndSend(INVALIDATE_CHANNEL, key);
			} catch (Exception e) {
				log.warn("广播缓存失效消息失败 key={}", key, e);
			}
		}
		log.debug("已清除多级缓存: {}", key);
	}

	/**
	 * 仅清本地 L1（Pub/Sub 订阅回调使用，避免死循环：发送方已 evict 自身，订阅方只需清 L1）
	 */
	public void invalidateLocalOnly(String key) {
		localCache.invalidate(key);
	}

	/** 集群 Pub/Sub 通道：value = 被失效的 cache key */
	public static final String INVALIDATE_CHANNEL = "cache:invalidate";

	/**
	 * 清除所有缓存（多级）
	 */
	public void evictAll() {
		localCache.invalidateAll();
		if (redisTemplate != null) {
			// 注意：生产环境谨慎使用，这里仅清除特定前缀的键
			log.warn("清除所有缓存功能已禁用，请使用 evict(key) 清除特定缓存");
		}
	}

	/**
	 * 获取缓存统计信息
	 */
	public String getStats() {
		return localCache.stats().toString();
	}
}
