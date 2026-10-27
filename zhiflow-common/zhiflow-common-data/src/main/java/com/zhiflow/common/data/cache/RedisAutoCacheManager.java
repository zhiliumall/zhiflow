package com.zhiflow.common.data.cache;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * redis cache 扩展cache name自动化配置
 * 租户ID追加
 * @author
 * <p>
 * cachename = xx#ttl
 */
@Slf4j
public class RedisAutoCacheManager extends RedisCacheManager {
	private static final String SPLIT_FLAG = "#";
	private static final int CACHE_LENGTH = 2;

	private final RedisCacheWriter cacheWriter;
	private final RedisCacheConfiguration defaultCacheConfiguration;

	/** 用户 / 令牌缓存被清时的广播（压测优化 A2），见 {@link DefensiveRedisCache}；null = 不广播。 */
	@Nullable
	private final Consumer<String> invalidationBroadcaster;

	/** 进程内 L1 名单（压测优化 C-L1），见 {@link RedisL1Settings}；null = 永不加 L1。 */
	@Nullable
	private final RedisL1Settings l1Settings;

	/** 每个缓存名一个 L1 包装，包的是本管理器 cacheMap 里那个 {@link DefensiveRedisCache}。 */
	private final ConcurrentMap<String, TwoLevelRedisCache> l1Caches = new ConcurrentHashMap<>();

	RedisAutoCacheManager(RedisCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration,
						  Map<String, RedisCacheConfiguration> initialCacheConfigurations, boolean allowInFlightCacheCreation) {
		this(cacheWriter, defaultCacheConfiguration, initialCacheConfigurations, allowInFlightCacheCreation, null, null);
	}

	RedisAutoCacheManager(RedisCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration,
						  Map<String, RedisCacheConfiguration> initialCacheConfigurations, boolean allowInFlightCacheCreation,
						  @Nullable Consumer<String> invalidationBroadcaster, @Nullable RedisL1Settings l1Settings) {
		// Spring Data Redis 4：构造器参数顺序改为 (writer, default, allowRuntimeCacheCreation, initialCaches)
		super(cacheWriter, defaultCacheConfiguration, allowInFlightCacheCreation, initialCacheConfigurations);
		this.cacheWriter = cacheWriter;
		this.defaultCacheConfiguration = defaultCacheConfiguration;
		this.invalidationBroadcaster = invalidationBroadcaster;
		this.l1Settings = l1Settings;
	}

	/**
	 * 缓存过期时间支持
	 * 如：@Cacheable(value = VALUE_NAME#TTL, key = "#PARAMS")
	 *    @Cacheable(value = user#1000, key = "#id")：过期时间1秒
	 * 同时把 RedisCache 替换为 DefensiveRedisCache，使脏 key 反序列化失败时不打 500。
	 * @param name
	 * @param cacheConfig
	 * @return
	 */
	@Override
	protected RedisCache createRedisCache(String name, @Nullable RedisCacheConfiguration cacheConfig) {
		RedisCacheConfiguration config = cacheConfig != null ? cacheConfig : defaultCacheConfiguration;

		// 仅在显式传入 cacheConfig 时解析 name#TTL，保持原 RedisAutoCacheManager 行为。
		if (cacheConfig != null && !StrUtil.isBlank(name) && name.contains(SPLIT_FLAG)) {
			String[] cacheArray = name.split(SPLIT_FLAG);
			if (cacheArray.length >= CACHE_LENGTH) {
				long cacheAge = Long.parseLong(cacheArray[1]);
				config = config.entryTtl(Duration.ofSeconds(cacheAge));
			}
		}

		return new DefensiveRedisCache(name, cacheWriter, config, invalidationBroadcaster);
	}

	/**
	 * 重写@Cacheable value 值，是否追加租户ID
	 * 例如 @Cacheable(value = CacheConstants.PUBLIC_CACHE + value)
	 * 	   如果value值前加了CacheConstants.PUBLIC_CACHE标识就不会自动在key前追加:租户ID
	 * @param name
	 * @return
	 */
	@Override
	public Cache getCache(String name) {
		if (name.startsWith(CacheConstants.PUBLIC_CACHE)) {
			return withL1(super.getCache(name), name);
		}
		String tenantId = TenantContextHolder.getTenantId();
		if (StrUtil.isBlank(tenantId)) {
			// 无租户上下文：Actuator 启动期绑 cache metrics、定时任务等场景。
			// 降级到不加前缀的 cache，避免阻塞应用启动；业务 HTTP 请求由 TenantContextHolderFilter 保证有租户上下文。
			log.debug("Cache[{}] 获取时无租户上下文，降级到无前缀缓存", name);
			return withL1(super.getCache(name), name);
		}
		return withL1(super.getCache(StrUtil.format("{}:{}", tenantId, name)), name);
	}

	/**
	 * 名单里的缓存名（此刻）→ 返回 L1 包装；否则原样返回。
	 *
	 * <p>刻意在 {@code getCache} 里包，而不是覆盖 {@code decorateCache}：
	 * <ul>
	 * <li>{@code decorateCache} 只在缓存创建时跑一次，名单就只能靠重启生效；这里每次取缓存都问一遍（名单最多每秒重读），
	 *     配置改完约 1 秒内开 / 关都生效。{@code @Cacheable} 每次调用都会经 {@code getCache} 解析缓存，不会缓存 Cache 对象。</li>
	 * <li>管理器内部 cacheMap 里存的仍是 {@link DefensiveRedisCache}：{@code RedisCacheManager#getCacheConfigurations()}
	 *     会把 cacheMap 里的值强转成 {@code RedisCache}，用 {@code decorateCache} 换成包装类会让它抛 ClassCastException。</li>
	 * </ul>
	 * 默认名单为空：每次多一次 volatile 读 + 一次集合查找，别的什么都不变。
	 *
	 * <p>名单按<b>加租户前缀之前</b>的名字比（{@code baseName}，即 {@code @Cacheable} 上写的那个）；
	 * 包装与代号仍按带前缀的完整名字，每租户一份。
	 */
	@Nullable
	private Cache withL1(@Nullable Cache cache, String baseName) {
		if (this.l1Settings == null || !(cache instanceof DefensiveRedisCache redisCache)
				|| !this.l1Settings.isEnabledFor(baseName)) {
			return cache;
		}
		TwoLevelRedisCache wrapper = this.l1Caches.get(redisCache.getName());
		if (wrapper != null && wrapper.wraps(redisCache)) {
			return wrapper;
		}
		// 首次，或底层缓存实例换过（resetCaches 之类）：按当前实例重建
		return this.l1Caches.compute(redisCache.getName(),
				(n, existing) -> existing != null && existing.wraps(redisCache) ? existing
						: new TwoLevelRedisCache(redisCache, RedisL1Settings.ALLOWED.get(baseName)));
	}
}
