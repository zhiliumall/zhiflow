package com.zhiflow.common.data.cache;

import com.zhiflow.common.core.constant.CacheConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压测优化 C-L1：{@code public:menu_cache} 前面的进程内 L1，跨实例失效靠同前缀下的代号标记。
 *
 * <p>两台 upms 用两个 {@link TwoLevelRedisCache} 模拟，共用同一个 {@link InMemoryRedisCacheWriter}（= 同一台 valkey）。
 * 「命中」的判据是<b>拿回同一个对象实例</b>（L2 每次反序列化都是新对象），并辅以 L2 数据键的 GET 次数。
 */
class TwoLevelRedisCacheTest {

	private static final String NAME = CacheConstants.MENU_CACHE;

	private static final String KEY = "1_menu";

	private static final String DATA_KEY = NAME + "::" + KEY;

	private static final String MARKER_KEY = NAME + "::" + TwoLevelRedisCache.GENERATION_KEY;

	private InMemoryRedisCacheWriter redis;

	private TwoLevelRedisCache upms1;

	private TwoLevelRedisCache upms2;

	@BeforeEach
	void setUp() {
		this.redis = new InMemoryRedisCacheWriter();
		this.upms1 = new TwoLevelRedisCache(l2(this.redis));
		this.upms2 = new TwoLevelRedisCache(l2(this.redis));
		// 通过 L2 写入（与 @Cacheable 回填同一条路径）
		l2(this.redis).put(KEY, new ArrayList<>(List.of("menu-a", "menu-b")));
	}

	static DefensiveRedisCache l2(InMemoryRedisCacheWriter writer) {
		return new DefensiveRedisCache(NAME, writer, config());
	}

	static RedisCacheConfiguration config() {
		return RedisCacheConfiguration.defaultCacheConfig()
			.serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
			.serializeValuesWith(RedisSerializationContext.SerializationPair
				.fromSerializer(RedisTemplateConfig.createValkeyOptimizedJsonSerializer()));
	}

	@Test
	@DisplayName("判据自检：不加 L1 时每次读 L2 都是新对象 —— 「同一实例」才能当命中判据")
	void plainL2ReturnsNewInstanceEachRead() {
		DefensiveRedisCache plain = l2(this.redis);
		assertNotSame(plain.get(KEY).get(), plain.get(KEY).get());
	}

	@Test
	@DisplayName("对照：代号不变时第二次读命中 L1（同一实例，L2 数据键只读一次）")
	void hitReturnsSameInstance() {
		Cache.ValueWrapper first = this.upms1.get(KEY);
		Cache.ValueWrapper second = this.upms1.get(KEY);

		assertNotNull(first);
		assertSame(first.get(), second.get());
		assertEquals(1, this.redis.getsOf(DATA_KEY));
		assertEquals(List.of("menu-a", "menu-b"), first.get());
	}

	@Test
	@DisplayName("代号标记变了（被别处删掉）⇒ L1 不命中，回 L2 读新副本")
	void markerChangeMeansMiss() {
		Object before = this.upms1.get(KEY).get();
		assertSame(before, this.upms1.get(KEY).get());

		this.redis.store.remove(MARKER_KEY);

		Object after = this.upms1.get(KEY).get();
		assertNotSame(before, after);
		assertEquals(2, this.redis.getsOf(DATA_KEY));
	}

	@Test
	@DisplayName("运维按 public:menu_cache::* 批量删 ⇒ 标记一起没了，两台都不再给旧值")
	void opsPatternDeleteDropsMarker() {
		this.upms1.get(KEY);
		this.upms2.get(KEY);
		assertTrue(this.redis.store.containsKey(MARKER_KEY));

		this.redis.clear(NAME, (NAME + "::*").getBytes());
		l2(this.redis).put(KEY, new ArrayList<>(List.of("menu-new")));

		assertEquals(List.of("menu-new"), this.upms1.get(KEY).get());
		assertEquals(List.of("menu-new"), this.upms2.get(KEY).get());
	}

	@Test
	@DisplayName("一台 evict ⇒ 另一台下一次读就看到新值")
	void evictOnOneInstanceIsSeenByTheOther() {
		assertEquals(List.of("menu-a", "menu-b"), this.upms2.get(KEY).get());

		this.upms1.evict(KEY);
		assertFalse(this.redis.store.containsKey(MARKER_KEY), "evict 必须删掉代号标记");
		l2(this.redis).put(KEY, new ArrayList<>(List.of("menu-c")));

		assertEquals(List.of("menu-c"), this.upms2.get(KEY).get());
	}

	@Test
	@DisplayName("clear ⇒ 标记被删；另一台下一次读 miss")
	void clearDeletesMarker() {
		this.upms2.get(KEY);
		this.upms1.get(KEY);
		assertTrue(this.redis.store.containsKey(MARKER_KEY));

		this.upms1.clear();

		assertFalse(this.redis.store.containsKey(MARKER_KEY));
		assertFalse(this.redis.store.containsKey(DATA_KEY));
		assertEquals(null, this.upms2.get(KEY), "L2 已空，另一台不能再从 L1 给出旧值");
	}

	@Test
	@DisplayName("evictIfPresent / invalidate 同样删标记")
	void otherEvictionsDeleteMarker() {
		this.upms1.get(KEY);
		this.upms1.evictIfPresent("not-there");
		assertFalse(this.redis.store.containsKey(MARKER_KEY));

		this.upms1.get(KEY);
		this.upms1.invalidate();
		assertFalse(this.redis.store.containsKey(MARKER_KEY));
	}

	@Test
	@DisplayName("Redis 不可用（读不到代号）⇒ 绕过 L1，退回 L2 行为")
	void redisDownBypassesL1() {
		Object cached = this.upms1.get(KEY).get();
		// 对照：正常时命中 L1
		assertSame(cached, this.upms1.get(KEY).get());

		// 只让代号键读失败（L2 数据键仍可读）：必须看到 L2 的新副本而不是 L1 里那个
		this.redis.failing = MARKER_KEY::equals;
		Object bypassed = this.upms1.get(KEY).get();
		assertNotSame(cached, bypassed);
		assertEquals(cached, bypassed);

		// 整台 Redis 不可用：与没有 L1 时一样，异常照常抛给 CacheErrorHandler（按 miss 处理）
		this.redis.failing = key -> true;
		assertThrows(RuntimeException.class, () -> this.upms1.get(KEY));
	}

	@Test
	@DisplayName("put 的活对象不进 L1：下一次读拿到的是 L2 反序列化出的副本")
	void putDoesNotShareCallersObject() {
		List<String> callersList = new ArrayList<>(List.of("x"));
		this.upms1.put("2_menu", callersList);

		Object read = this.upms1.get("2_menu").get();

		assertNotSame(callersList, read);
		assertEquals(callersList, read);
	}

	@Test
	@DisplayName("容量按元素数计权：一条大菜单不能只算 1")
	void weightFollowsElementCount() {
		assertEquals(4, TwoLevelRedisCache.weigh(List.of("a", "b", "c")));
		assertEquals(3, TwoLevelRedisCache.weigh(com.zhiflow.common.core.util.R.ok(List.of("a", "b"))));
		assertEquals(1, TwoLevelRedisCache.weigh("scalar"));
		assertEquals(1, TwoLevelRedisCache.weigh(null));
	}

	@Test
	@DisplayName("get(key, loader)：L2 有值时进 L1，第二次不再读 L2")
	void valueLoaderPathUsesL1() {
		Object first = this.upms1.get(KEY, () -> List.of("should-not-load"));
		Object second = this.upms1.get(KEY, () -> List.of("should-not-load"));

		assertSame(first, second);
		assertEquals(1, this.redis.getsOf(DATA_KEY));
	}

}
