package com.zhiflow.common.data.cache;

import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.security.component.AuthorizationSnapshotCache;
import com.zhiflow.common.security.component.AuthorizationSnapshotInvalidationListener;
import com.zhiflow.common.security.component.TokenTenant;
import com.zhiflow.common.security.entity.BaseUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.authority.AuthorityUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 压测优化 A2 的发布端：{@code public:user_cache} / 令牌缓存一被清就在 {@code cache:invalidate} 上广播，
 * 且消息编码与老订阅者（{@code CacheInvalidateListenerConfig}）、新订阅者都兼容。
 */
class CacheInvalidationBroadcastTest {

	private static DefensiveRedisCache cache(String name, InMemoryRedisCacheWriter writer, List<String> sink) {
		RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
			.serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
			.serializeValuesWith(RedisSerializationContext.SerializationPair
				.fromSerializer(RedisTemplateConfig.createValkeyOptimizedJsonSerializer()));
		return new DefensiveRedisCache(name, writer, config, sink::add);
	}

	@Test
	@DisplayName("user_cache：evict / evictIfPresent / clear / invalidate 各广播一条，clear 不重复")
	void userCacheEvictionsAreBroadcast() {
		List<String> sent = new ArrayList<>();
		DefensiveRedisCache userCache = cache(CacheConstants.USER_CACHE, new InMemoryRedisCacheWriter(), sent);

		userCache.evict("admin");
		userCache.evictIfPresent("13800000000");
		userCache.clear();
		userCache.invalidate();

		assertEquals(List.of("public:user_cache::admin", "public:user_cache::13800000000", "public:user_cache::*",
				"public:user_cache::*"), sent);
	}

	@Test
	@DisplayName("令牌缓存（删角色全员登出走 allEntries）同样广播")
	void tokenCacheClearIsBroadcast() {
		List<String> sent = new ArrayList<>();
		cache(CacheConstants.OAUTH_CLIENT_CACHE, new InMemoryRedisCacheWriter(), sent).clear();

		assertEquals(List.of("public:token::access_token::*"), sent);
	}

	@Test
	@DisplayName("对照：别的缓存（菜单）清掉不广播，put 也不广播")
	void otherCachesAreSilent() {
		List<String> sent = new ArrayList<>();
		DefensiveRedisCache menu = cache(CacheConstants.MENU_CACHE, new InMemoryRedisCacheWriter(), sent);
		menu.evict("1_menu");
		menu.clear();
		DefensiveRedisCache userCache = cache(CacheConstants.USER_CACHE, new InMemoryRedisCacheWriter(), sent);
		userCache.put("admin", "x");

		assertTrue(sent.isEmpty(), () -> "不该广播: " + sent);
	}

	@Test
	@DisplayName("Redis 删失败时不广播（异常照常抛给 CacheErrorHandler）；广播失败不影响清缓存本身")
	void broadcastOnlyAfterSuccessfulEviction() {
		List<String> sent = new ArrayList<>();
		InMemoryRedisCacheWriter writer = new InMemoryRedisCacheWriter();
		writer.failing = key -> true;
		DefensiveRedisCache userCache = cache(CacheConstants.USER_CACHE, writer, sent);
		try {
			userCache.evict("admin");
		}
		catch (RuntimeException expected) {
			// 与改造前相同：交给 CacheErrorHandler
		}
		assertTrue(sent.isEmpty());

		InMemoryRedisCacheWriter healthy = new InMemoryRedisCacheWriter();
		healthy.store.put("public:user_cache::admin", new byte[] { 1 });
		RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
			.serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));
		DefensiveRedisCache broken = new DefensiveRedisCache(CacheConstants.USER_CACHE, healthy, config, message -> {
			throw new IllegalStateException("publish failed");
		});
		broken.evict("admin");
		assertNull(healthy.store.get("public:user_cache::admin"));
	}

	@Test
	@DisplayName("广播器：先同步作废本进程内省缓存，再用主 RedisTemplate convertAndSend 到 cache:invalidate")
	@SuppressWarnings("unchecked")
	void broadcasterUsesConvertAndSendOnTheExistingChannel() {
		RedisTemplate<String, Object> template = mock(RedisTemplate.class);
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty(AuthorizationSnapshotCache.ENABLED_PROPERTY, "true");
		AuthorizationSnapshotCache snapshots = new AuthorizationSnapshotCache(environment, template);
		snapshots.put("k", snapshots.epoch(), new TokenTenant("1", "1", null), user());

		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		beans.registerSingleton("redisTemplate", template);
		beans.registerSingleton("authorizationSnapshotCache", snapshots);
		ObjectProvider<RedisTemplate<String, Object>> templates = (ObjectProvider<RedisTemplate<String, Object>>) (ObjectProvider<?>) beans
			.getBeanProvider(RedisTemplate.class);

		RedisCacheAutoConfiguration
			.invalidationBroadcaster(templates, beans.getBeanProvider(AuthorizationSnapshotCache.class))
			.accept("public:user_cache::admin");

		verify(template).convertAndSend("cache:invalidate", "public:user_cache::admin");
		assertNull(snapshots.getIfAlive("k", "t", Boolean.TRUE), "本进程的内省缓存必须同步作废");
	}

	@Test
	@DisplayName("编码兼容：主 RedisTemplate 序列化器写出的消息，老订阅者解得出原字符串，新订阅者能识别")
	void wireFormatIsUnderstoodByOldAndNewSubscribers() {
		// convertAndSend 用的是主 RedisTemplate 的 valueSerializer，也就是这个
		GenericJacksonJsonRedisSerializer serializer = RedisTemplateConfig.createValkeyOptimizedJsonSerializer();
		byte[] body = serializer.serialize("public:user_cache::admin");

		// 老订阅者（CacheInvalidateListenerConfig）：deserialize 得到 String 才去清 L1，否则打 WARN
		assertEquals("public:user_cache::admin", serializer.deserialize(body));

		// 新订阅者：识别出来并整体作废
		MockEnvironment environment = new MockEnvironment();
		AuthorizationSnapshotCache snapshots = new AuthorizationSnapshotCache(environment, mock(RedisTemplate.class));
		snapshots.put("k", snapshots.epoch(), null, user());
		assertNotNull(snapshots.getIfAlive("k", "t", Boolean.TRUE));
		new AuthorizationSnapshotInvalidationListener(snapshots).onMessage(
				new DefaultMessage("cache:invalidate".getBytes(StandardCharsets.UTF_8), body), null);
		assertNull(snapshots.getIfAlive("k", "t", Boolean.TRUE));
	}

	@Test
	@DisplayName("common-security 里另存的频道名与 MultiLevelCacheService 的是同一个")
	void channelNamesAgree() {
		assertEquals(MultiLevelCacheService.INVALIDATE_CHANNEL, AuthorizationSnapshotCache.INVALIDATE_CHANNEL);
	}

	private static BaseUser user() {
		return new BaseUser("1", "0", "1", "1", "admin", "x", null, true, true, true, true,
				AuthorityUtils.createAuthorityList("sys:user:add"));
	}

}
