package com.zhiflow.common.data.cache;

import com.zhiflow.common.core.constant.CacheConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.lang.Nullable;

import java.util.Set;
import java.util.function.Consumer;

/**
 * 反序列化容错的 RedisCache：
 * Redis 里如果残留了不兼容序列化器写入的旧字节（典型：JDK 序列化 `0xAC 0xED…` 被 Jackson 读到），
 * 原生 RedisCache 会向上抛 SerializationException 直接打 500。
 * 这里捕获后清掉脏 key，按 cache miss 处理，由 @Cacheable 回源 DB 重新写入。
 *
 * <h2>用户 / 令牌缓存被清时广播（压测优化 A2）</h2>
 * <p>各资源服务的令牌内省结果会在进程内缓存 10 秒（common-security 的 {@code AuthorizationSnapshotCache}），
 * 它的内容来自 {@code public:user_cache} 与 {@code public:token::access_token}。所以这两份缓存<b>一被清</b>
 * （evict / evictIfPresent / clear / invalidate），就在现有频道 {@code cache:invalidate} 上发一条消息，
 * 让各实例整体作废。upms / auth 里全部清缓存点都经过这里，<b>一行不用改</b>就接上了。
 *
 * <p>发布<b>不看</b>内省缓存开关：开关是按服务的，发布端（upms / auth）关着、订阅端（mall-admin）开着时也必须发。
 * 消息体与 {@code MultiLevelCacheService.evict} 同一个 {@code redisTemplate.convertAndSend}，
 * 老订阅者（mall-admin、mall-api）收到的是一个它没有的 L1 键，清一下无副作用、不打 WARN。
 *
 * <h2>白名单缓存清除时删 L1 代号</h2>
 * <p>缓存名在 L1 白名单（{@link RedisL1Settings#ALLOWED}）里时，evict / evictIfPresent / clear / invalidate 成功后
 * 再 DEL 一次 {@code <缓存名>::__l1_gen}，<b>不看本进程开没开 L1</b>：读的进程（开 L1）与清的进程（可能没开）不一定是同一个，
 * 只靠读方的 {@link TwoLevelRedisCache} 删代号，清方没开时读方的 L1 会一直给旧值到过期。
 */
@Slf4j
public class DefensiveRedisCache extends RedisCache {

	/** 被清时要广播的缓存名。 */
	static final Set<String> BROADCAST_CACHE_NAMES = Set.of(CacheConstants.USER_CACHE,
			CacheConstants.OAUTH_CLIENT_CACHE);

	/** 非 null 仅当本缓存名在 {@link #BROADCAST_CACHE_NAMES} 里。参数是消息体，形如 {@code public:user_cache::admin}。 */
	@Nullable
	private final Consumer<String> invalidationBroadcaster;

	/** 本缓存名在 L1 白名单里时是代号键，否则 null（清除时不删代号）。 */
	@Nullable
	private final byte[] l1GenerationKeyToDrop;

	protected DefensiveRedisCache(String name, RedisCacheWriter cacheWriter, RedisCacheConfiguration cacheConfig) {
		this(name, cacheWriter, cacheConfig, null);
	}

	DefensiveRedisCache(String name, RedisCacheWriter cacheWriter, RedisCacheConfiguration cacheConfig,
			@Nullable Consumer<String> invalidationBroadcaster) {
		super(name, cacheWriter, cacheConfig);
		this.invalidationBroadcaster = BROADCAST_CACHE_NAMES.contains(name) ? invalidationBroadcaster : null;
		this.l1GenerationKeyToDrop = RedisL1Settings.allowsL1(name) ? l1GenerationKey() : null;
	}

	@Override
	protected Object lookup(Object key) {
		try {
			return super.lookup(key);
		} catch (SerializationException ex) {
			log.warn("Cache[{}] key[{}] 反序列化失败，删除脏 key 后回源: {}", getName(), key, ex.getMessage());
			try {
				// 用 super.evict：脏数据从没进过任何进程内缓存，不需要广播；
				// 走本类 evict 会每个请求都发一次失效广播，把全部订阅者的缓存一起冲掉
				super.evict(key);
			} catch (Exception evictEx) {
				log.warn("Cache[{}] key[{}] evict 失败: {}", getName(), key, evictEx.getMessage());
			}
			return null;
		}
	}

	@Override
	public void evict(Object key) {
		super.evict(key);
		dropL1GenerationIfAllowed();
		broadcastInvalidation(key);
	}

	@Override
	public boolean evictIfPresent(Object key) {
		boolean evicted = super.evictIfPresent(key);
		// 不管 L2 里有没有：别的实例的 L1 可能还留着这条（L2 已按 TTL 过期）
		dropL1GenerationIfAllowed();
		broadcastInvalidation(key);
		return evicted;
	}

	/** {@code clear()} 在父类里就是 {@code clear("*")}，只覆盖这一个即可，不会发两遍。 */
	@Override
	public void clear(String keyPattern) {
		super.clear(keyPattern);
		// 按 * 删时代号已经一起没了；按别的模式删时要补删
		dropL1GenerationIfAllowed();
		broadcastInvalidation(keyPattern);
	}

	@Override
	public boolean invalidate() {
		boolean invalidated = super.invalidate();
		dropL1GenerationIfAllowed();
		broadcastInvalidation("*");
		return invalidated;
	}

	private void dropL1GenerationIfAllowed() {
		if (this.l1GenerationKeyToDrop != null) {
			dropL1Generation();
		}
	}

	/**
	 * Redis 操作成功之后再发（先删 L2 再通知，别的实例回源时读到的就是新值）。
	 * 发布失败只记日志：本次清缓存本身已经成功，接收端还有 10 秒 TTL 兜底。
	 */
	private void broadcastInvalidation(Object key) {
		if (this.invalidationBroadcaster == null) {
			return;
		}
		String message;
		try {
			message = getName() + "::" + convertKey(key);
		}
		catch (RuntimeException ex) {
			message = getName() + "::*";
		}
		try {
			this.invalidationBroadcaster.accept(message);
		}
		catch (RuntimeException ex) {
			log.warn("Cache[{}] 失效广播发送失败（各实例内省缓存最多晚 10 秒失效）: {}", getName(), ex.toString());
		}
	}

	// ---------------------------------------------------------------- 给 TwoLevelRedisCache 用

	/** 同一个前缀下的 L1 代号标记键：{@code <缓存名>::__l1_gen}（按 {@code <缓存名>::*} 批量删时会被一起删掉）。 */
	byte[] l1GenerationKey() {
		return serializeCacheKey(createCacheKey(TwoLevelRedisCache.GENERATION_KEY));
	}

	/** 本缓存名在 L1 白名单里：清除时已经删了代号，包它的 {@link TwoLevelRedisCache} 不必再删。 */
	boolean dropsL1Generation() {
		return this.l1GenerationKeyToDrop != null;
	}

	/**
	 * 删掉代号标记，让所有实例的 L1 在下一次访问时失效。
	 * 删不掉（Redis 在 L2 操作成功后恰好出错）只记日志：别的实例这一批最多旧到 L1 过期。
	 */
	void dropL1Generation() {
		try {
			getCacheWriter().evict(getName(),
					this.l1GenerationKeyToDrop != null ? this.l1GenerationKeyToDrop : l1GenerationKey());
		}
		catch (RuntimeException ex) {
			log.warn("Cache[{}] 删除 L1 代号标记失败，其它实例的 L1 要到写后过期才失效: {}", getName(), ex.toString());
		}
	}

	/** 代号值按本缓存的值序列化器写（一个 JSON 字符串），扫这个前缀的人读得出来。 */
	byte[] l1GenerationValue(String generation) {
		return serializeCacheValue(generation);
	}

}
