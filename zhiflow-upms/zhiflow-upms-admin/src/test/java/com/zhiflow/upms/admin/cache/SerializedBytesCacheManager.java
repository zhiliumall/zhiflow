package com.zhiflow.upms.admin.cache;

import com.zhiflow.common.data.cache.RedisTemplateConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 测试用缓存：值按<b>生产同一个</b>序列化器（{@link RedisTemplateConfig#createValkeyOptimizedJsonSerializer()}）
 * 存成字节、读时再反序列化——与 {@code RedisCache} 命中时的往返一致。
 *
 * <p>用 {@code ConcurrentMapCacheManager} 会直接把对象引用存进去，命中时拿到的就是写入的那个对象，
 * 「缓存读回来是不是和算出来的一样」这件事就测不到了。
 */
public final class SerializedBytesCacheManager implements CacheManager {

	@SuppressWarnings("unchecked")
	private final RedisSerializer<Object> serializer = (RedisSerializer<Object>) (RedisSerializer<?>) RedisTemplateConfig
			.createValkeyOptimizedJsonSerializer();

	private final ConcurrentMap<String, BytesCache> caches = new ConcurrentHashMap<>();

	@Override
	public BytesCache getCache(String name) {
		return caches.computeIfAbsent(name, n -> new BytesCache(n, serializer));
	}

	@Override
	public Collection<String> getCacheNames() {
		return caches.keySet();
	}

	public RedisSerializer<Object> serializer() {
		return serializer;
	}

	public static final class BytesCache extends AbstractValueAdaptingCache {

		private final String name;

		private final RedisSerializer<Object> serializer;

		private final ConcurrentMap<Object, byte[]> store = new ConcurrentHashMap<>();

		BytesCache(String name, RedisSerializer<Object> serializer) {
			super(false);
			this.name = name;
			this.serializer = serializer;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public Object getNativeCache() {
			return store;
		}

		@Override
		protected Object lookup(Object key) {
			byte[] bytes = store.get(key);
			return bytes == null ? null : serializer.deserialize(bytes);
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> T get(Object key, Callable<T> valueLoader) {
			Object hit = lookup(key);
			if (hit != null) {
				return (T) hit;
			}
			try {
				T value = valueLoader.call();
				put(key, value);
				return value;
			} catch (Exception e) {
				throw new ValueRetrievalException(key, valueLoader, e);
			}
		}

		@Override
		public void put(Object key, Object value) {
			store.put(key, serializer.serialize(toStoreValue(value)));
		}

		@Override
		public void evict(Object key) {
			store.remove(key);
		}

		@Override
		public void clear() {
			store.clear();
		}

		/** 当前所有键（排好序，便于断言）。 */
		public Set<String> keys() {
			Set<String> keys = new TreeSet<>();
			store.keySet().forEach(k -> keys.add(String.valueOf(k)));
			return keys;
		}

		/** 某个键存的原始字节（与 Redis 里 GET 到的一致）。 */
		public byte[] rawBytes(Object key) {
			return store.get(key);
		}
	}
}
