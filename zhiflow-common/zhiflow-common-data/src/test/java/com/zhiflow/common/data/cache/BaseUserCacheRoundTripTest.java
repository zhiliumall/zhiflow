package com.zhiflow.common.data.cache;

import com.zhiflow.common.security.entity.BaseUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.core.authority.AuthorityUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死「登录态能否从 Redis 缓存读回」。
 *
 * <p>背景（2026-09-12 的一次事故）：Jackson 3 迁移的 OpenRewrite 把 {@code BaseUserMixin} 上的
 * {@code @JsonDeserialize} 换成了 Jackson 3 的 {@code tools.jackson.databind.annotation} 版本，
 * 而**缓存层的 ObjectMapper 仍是 Jackson 2**，认不出这个注解。于是 Jackson 2 沿继承链拿到
 * spring-security {@code UserMixin} 上的 {@code @JsonDeserialize(using = UserDeserializer.class)}，
 * 把 {@code BaseUser} 反序列化成父类 {@code User}。
 * {@code BaseUserDetailsServiceImpl.loadUserByUsername} 的 {@code (BaseUser) cache.get(..)} 当场
 * ClassCastException → introspect 失败 → 管理后台每个带令牌的请求 424，登录即被踢出。
 *
 * <p>判据必须是「读回来的对象是不是 BaseUser」而不是「有没有抛异常」：
 * 反序列化本身是成功的，坏的是**类型**。
 */
class BaseUserCacheRoundTripTest {

	private static BaseUser sampleUser() {
		return new BaseUser("1000000000000000004", "1", "-1", "-1",
				"system", "{bcrypt}$2a$10$xxxxxxxxxxxxxxxxxxxxxx", List.of("1", "-1"),
				true, true, true, true,
				AuthorityUtils.createAuthorityList("ROLE_ADMIN", "sys_user_add"));
	}

	@Test
	@DisplayName("缓存序列化器往返后仍是 BaseUser（而不是被降级成 spring-security 的 User）")
	void baseUserSurvivesCacheRoundTrip() {
		RedisSerializer<Object> serializer = cacheSerializer();

		byte[] bytes = serializer.serialize(sampleUser());
		String json = new String(bytes, StandardCharsets.UTF_8);
		assertTrue(json.contains(BaseUser.class.getName()),
				"序列化结果里应带 BaseUser 的类型头，实际=" + json);

		Object back = serializer.deserialize(bytes);

		assertInstanceOf(BaseUser.class, back,
				"缓存读回的不是 BaseUser 而是 " + (back == null ? "null" : back.getClass().getName())
						+ " —— BaseUserMixin 的 @JsonDeserialize 必须是缓存 mapper 同一套 Jackson 的注解");

		BaseUser restored = (BaseUser) back;
		assertEquals("1000000000000000004", restored.getId());
		assertEquals("system", restored.getUsername());
		assertEquals("-1", restored.getTenantId());
		assertEquals(List.of("1", "-1"), restored.getTenantIds());
		assertEquals(2, restored.getAuthorities().size(), "authorities 丢了就等于权限全没了");
	}

	@SuppressWarnings("unchecked")
	private static RedisSerializer<Object> cacheSerializer() {
		return (RedisSerializer<Object>) (RedisSerializer<?>) RedisTemplateConfig
				.createValkeyOptimizedJsonSerializer();
	}
}
