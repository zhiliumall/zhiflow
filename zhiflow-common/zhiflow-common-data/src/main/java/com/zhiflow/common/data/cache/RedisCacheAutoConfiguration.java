package com.zhiflow.common.data.cache;

import com.zhiflow.common.security.component.AuthorizationSnapshotCache;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizers;
import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 扩展redis-cache支持注解cacheName添加超时时间
 *
 * @author
 */
@AutoConfiguration(after = {DataRedisAutoConfiguration.class}, before = {CacheAutoConfiguration.class})
@ConditionalOnClass(RedisConnectionFactory.class)
@EnableConfigurationProperties(CacheProperties.class)
public class RedisCacheAutoConfiguration {
	private final CacheProperties cacheProperties;
	private final CacheManagerCustomizers customizerInvoker;
	@Nullable
	private final RedisCacheConfiguration redisCacheConfiguration;

	RedisCacheAutoConfiguration(CacheProperties cacheProperties,
								ObjectProvider<CacheManagerCustomizers> customizerInvoker,
								ObjectProvider<RedisCacheConfiguration> redisCacheConfiguration) {
		this.cacheProperties = cacheProperties;
		this.customizerInvoker = customizerInvoker.getIfAvailable(() -> new CacheManagerCustomizers(java.util.Collections.emptyList()));
		this.redisCacheConfiguration = redisCacheConfiguration.getIfAvailable();
	}

	/**
	 * @param redisTemplateProvider 主 RedisTemplate（JSON）。用它发用户 / 令牌缓存的失效广播，
	 * 与 {@link MultiLevelCacheService#evict} 同一个 {@code convertAndSend}；按需取，不在这里建立依赖
	 * @param snapshotCacheProvider 压测优化 A2 的内省缓存（只有资源服务有）。本进程清用户 / 令牌缓存时先同步作废它，
	 * 不等自己发的广播绕一圈回来
	 * @param environment 读 C-L1 名单（{@link RedisL1Settings}）
	 */
	@Bean(name = "cacheManager")
	@Primary
	public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, ResourceLoader resourceLoader,
			ObjectProvider<RedisTemplate<String, Object>> redisTemplateProvider,
			ObjectProvider<AuthorizationSnapshotCache> snapshotCacheProvider, Environment environment) {
		RedisCacheWriter redisCacheWriter = RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory);
		RedisCacheConfiguration cacheConfiguration = this.determineConfiguration(resourceLoader.getClassLoader());
		List<String> cacheNames = this.cacheProperties.getCacheNames();
		Map<String, RedisCacheConfiguration> initialCaches = new LinkedHashMap<>();
		if (!cacheNames.isEmpty()) {
			Map<String, RedisCacheConfiguration> cacheConfigMap = new LinkedHashMap<>(cacheNames.size());
			cacheNames.forEach(it -> cacheConfigMap.put(it, cacheConfiguration));
			initialCaches.putAll(cacheConfigMap);
		}
		RedisAutoCacheManager cacheManager = new RedisAutoCacheManager(redisCacheWriter, cacheConfiguration,
				initialCaches, true, invalidationBroadcaster(redisTemplateProvider, snapshotCacheProvider),
				new RedisL1Settings(environment));
		cacheManager.setTransactionAware(false);
		org.slf4j.LoggerFactory.getLogger(RedisCacheAutoConfiguration.class)
				.info(">>> RedisCacheAutoConfiguration loaded — cacheManager 与 RedisTemplate 共用同一个 Jackson 3 序列化器");
		return (RedisCacheManager) this.customizerInvoker.customize(cacheManager);
	}

	/**
	 * 用户 / 令牌缓存被清时要做的事（只有这两个缓存名会调用它，见 {@link DefensiveRedisCache}）：
	 * 先同步作废本进程的内省缓存，再在 {@code cache:invalidate} 上广播给其它实例。
	 * 广播与现有 {@link MultiLevelCacheService#evict} 用同一个主 RedisTemplate 的 {@code convertAndSend}，
	 * 消息编码逐字节相同，老订阅者不会因为解不开而打 WARN。
	 */
	static Consumer<String> invalidationBroadcaster(ObjectProvider<RedisTemplate<String, Object>> redisTemplateProvider,
			ObjectProvider<AuthorizationSnapshotCache> snapshotCacheProvider) {
		return message -> {
			snapshotCacheProvider.ifAvailable(AuthorizationSnapshotCache::invalidateAll);
			RedisTemplate<String, Object> redisTemplate = redisTemplateProvider.getIfAvailable();
			if (redisTemplate != null) {
				redisTemplate.convertAndSend(MultiLevelCacheService.INVALIDATE_CHANNEL, message);
			}
		};
	}

	private RedisCacheConfiguration determineConfiguration(ClassLoader classLoader) {
		// 注意：不要走 `if (this.redisCacheConfiguration != null) return it` 分支 ——
		// Spring Boot 的 org.springframework.boot.cache.autoconfigure.RedisCacheConfiguration
		// 会自动注册一个使用 JdkSerializationRedisSerializer 的 RedisCacheConfiguration bean，
		// 若被 ObjectProvider 拿到，下方 Jackson 序列化器就被绕过了，与 RedisTemplate 主 Bean
		// 不一致，会导致跨服务/跨重启读到的 JSON 数据走 JDK 反序列化抛 StreamCorruptedException。
		CacheProperties.Redis redisProperties = this.cacheProperties.getRedis();
		RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig();

		config = config
			.serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
			.serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(RedisTemplateConfig.createValkeyOptimizedJsonSerializer()));

		if (redisProperties.getTimeToLive() != null) {
			config = config.entryTtl(redisProperties.getTimeToLive());
		}

		if (redisProperties.getKeyPrefix() != null) {
			config = config.prefixCacheNameWith(redisProperties.getKeyPrefix());
		}

		if (!redisProperties.isCacheNullValues()) {
			config = config.disableCachingNullValues();
		}

		if (!redisProperties.isUseKeyPrefix()) {
			config = config.disableKeyPrefix();
		}

		return config;
	}
}
