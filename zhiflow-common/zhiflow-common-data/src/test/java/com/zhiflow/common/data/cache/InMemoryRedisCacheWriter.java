package com.zhiflow.common.data.cache;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * 测试用的 Redis：一个进程内的 Map，多个「实例」共用同一个就等于共用同一台 valkey。
 * 可以让指定的键读写失败，模拟 Redis 不可用。
 */
final class InMemoryRedisCacheWriter implements RedisCacheWriter {

	final Map<String, byte[]> store = new ConcurrentHashMap<>();

	/** 每个键被 GET 的次数。 */
	final Map<String, AtomicInteger> gets = new ConcurrentHashMap<>();

	/** 命中的键一律抛连接异常。 */
	volatile Predicate<String> failing = key -> false;

	private void check(String key) {
		if (this.failing.test(key)) {
			throw new RedisConnectionFailureException("simulated: valkey down");
		}
	}

	private static String k(byte[] key) {
		return new String(key, StandardCharsets.UTF_8);
	}

	int getsOf(String key) {
		AtomicInteger n = this.gets.get(key);
		return n == null ? 0 : n.get();
	}

	@Override
	@Nullable
	public byte[] get(String name, byte[] key) {
		String k = k(key);
		check(k);
		this.gets.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet();
		return this.store.get(k);
	}

	@Override
	public CompletableFuture<byte[]> retrieve(String name, byte[] key, @Nullable Duration ttl) {
		return CompletableFuture.completedFuture(get(name, key));
	}

	@Override
	public void put(String name, byte[] key, byte[] value, @Nullable Duration ttl) {
		String k = k(key);
		check(k);
		this.store.put(k, value);
	}

	@Override
	public CompletableFuture<Void> store(String name, byte[] key, byte[] value, @Nullable Duration ttl) {
		put(name, key, value, ttl);
		return CompletableFuture.completedFuture(null);
	}

	@Override
	@Nullable
	public byte[] putIfAbsent(String name, byte[] key, byte[] value, @Nullable Duration ttl) {
		String k = k(key);
		check(k);
		return this.store.putIfAbsent(k, value);
	}

	@Override
	public void evict(String name, byte[] key) {
		String k = k(key);
		check(k);
		this.store.remove(k);
	}

	/** 只支持「前缀*」这种模式 —— RedisCache.clear() 与运维按前缀批量删用的都是它。 */
	@Override
	public void clear(String name, byte[] pattern) {
		String p = k(pattern);
		check(p);
		String prefix = p.endsWith("*") ? p.substring(0, p.length() - 1) : p;
		this.store.keySet().removeIf(key -> key.startsWith(prefix));
	}

	@Override
	public void clearStatistics(String name) {
	}

	@Override
	public RedisCacheWriter withStatisticsCollector(CacheStatisticsCollector cacheStatisticsCollector) {
		return this;
	}

	@Override
	public CacheStatistics getCacheStatistics(String cacheName) {
		throw new UnsupportedOperationException();
	}

}
