package com.zhiflow.common.data.cache;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.upms.common.entity.SysTenant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死「缓存层迁到 Jackson 3 之后，换包前就躺在 Redis 里的旧值还能不能读回」。
 *
 * <p>两边的角色：
 * <ul>
 *   <li><b>新代码</b> = {@link RedisTemplateConfig#createValkeyOptimizedJsonSerializer()}（生产同一份，Jackson 3）</li>
 *   <li><b>旧代码</b> = 本类里 {@link #legacyJackson2Serializer()} 复刻的迁移前配置（Jackson 2），
 *       用来生成「已有的旧格式缓存值」</li>
 * </ul>
 *
 * <p>大样本用从 Redis 按字节原样导出的缓存值（菜单树、字典，各几百 KB），
 * 放在 {@code -Dcache.fixtures.dir=<目录>}；没给就跳过，CI 不带样本也能跑。
 *
 * <p><b>判据刻意不是「两边字节相同」。</b>Jackson 3 默认按字母序排字段，换实现必然改变字节，
 * 逐字节比对会把完全正常的情况误报成不兼容（本实验最初就踩了这个）。正确判据是语义等价：
 * 把两边还原出的对象**用同一个 mapper 重写**再比字节。该判据已用「故意丢一个字段」的对照样本
 * 验证过确实会红，不是假绿。
 */
class CacheJackson3CompatExperimentTest {

	private static final Path FIXTURES = Path.of(
			System.getProperty("cache.fixtures.dir", "/nonexistent"));

	/** 生产同款：迁移后的 Jackson 3 缓存序列化器。 */
	@SuppressWarnings("unchecked")
	private static RedisSerializer<Object> jackson3Serializer() {
		return (RedisSerializer<Object>) (RedisSerializer<?>) RedisTemplateConfig
				.createValkeyOptimizedJsonSerializer();
	}

	/**
	 * 复刻迁移前的 Jackson 2 缓存序列化器 —— 代表「换包前那版 jar 写进 Redis 的值」。
	 * 这份复制品必须留着：没有它就没法证明新代码读得回旧值，只能靠清缓存绕过去。
	 */
	private static RedisSerializer<Object> legacyJackson2Serializer() {
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
		mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS);
		mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
		mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
		mapper.registerModules(org.springframework.security.jackson2.SecurityJackson2Modules
				.getModules(CacheJackson3CompatExperimentTest.class.getClassLoader()));
		mapper.addMixIn(BaseUser.class, LegacyJackson2BaseUserMixin.class);
		PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
				.allowIfBaseType("com.zhiflow.")
				.allowIfBaseType("org.springframework.security.")
				.allowIfSubType("com.zhiflow.")
				.allowIfSubType("org.springframework.security.")
				.allowIfSubType("java.util.")
				.allowIfSubType("java.lang.")
				.allowIfSubType("java.math.")
				.allowIfSubType("java.time.")
				.build();
		mapper.activateDefaultTyping(ptv,
				com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.NON_FINAL,
				JsonTypeInfo.As.PROPERTY);
		return new GenericJackson2JsonRedisSerializer(mapper);
	}

	/** 迁移前那版 BaseUserMixin：注解是 Jackson 2 的。放在测试里，正因为主代码那份已经迁走了。 */
	@com.fasterxml.jackson.databind.annotation.JsonDeserialize
	abstract static class LegacyJackson2BaseUserMixin {
		@JsonCreator
		LegacyJackson2BaseUserMixin(
				@JsonProperty("id") String id, @JsonProperty("organId") String organId,
				@JsonProperty("type") String type, @JsonProperty("tenantId") String tenantId,
				@JsonProperty("username") String username, @JsonProperty("password") String password,
				@JsonProperty("tenantIds") List<String> tenantIds,
				@JsonProperty("enabled") boolean enabled,
				@JsonProperty("accountNonExpired") boolean accountNonExpired,
				@JsonProperty("credentialsNonExpired") boolean credentialsNonExpired,
				@JsonProperty("accountNonLocked") boolean accountNonLocked,
				@JsonProperty("authorities") Collection<? extends GrantedAuthority> authorities) {
		}
	}

	private static byte[] fixture(String name) throws Exception {
		Path p = FIXTURES.resolve(name);
		Assumptions.assumeTrue(Files.exists(p), "缺样本 " + p + "（用 -Dcache.fixtures.dir 指定目录）");
		return Files.readAllBytes(p);
	}

	@Test
	@DisplayName("新代码能读回真实旧缓存：菜单树 372KB（ArrayList 多态头 + MenuVO + LocalDateTime）")
	void jackson3ReadsRealMenuCacheWrittenByJackson2() throws Exception {
		byte[] old = fixture("public_menu_cache__1_menu.json");

		Object viaNew = jackson3Serializer().deserialize(old);
		Object viaOld = legacyJackson2Serializer().deserialize(old);

		assertInstanceOf(List.class, viaNew, "顶层应还原成 List");
		assertFalse(((List<?>) viaNew).isEmpty(), "菜单列表不应为空");
		assertEquals("com.zhiflow.upms.common.vo.MenuVO",
				((List<?>) viaNew).get(0).getClass().getName(),
				"元素类型应由 @class 头还原成 MenuVO，而不是退化成 Map");
		assertEquals(((List<?>) viaOld).size(), ((List<?>) viaNew).size(), "条目数必须一致");

		// 语义判据：两边还原出的对象用同一个 mapper 重写，字节必须一致
		assertArrayEquals(legacyJackson2Serializer().serialize(viaOld),
				legacyJackson2Serializer().serialize(viaNew),
				"归一化后仍有差异 ⇒ Jackson 3 读出来的数据与 Jackson 2 不同，是真问题");
	}

	@Test
	@DisplayName("新代码能读回真实旧缓存：字典 377KB（R 包着 SysDictValue 列表）")
	void jackson3ReadsRealDictCacheWrittenByJackson2() throws Exception {
		byte[] old = fixture("public_dict_cache__zh-hans.json");

		Object viaNew = jackson3Serializer().deserialize(old);
		Object viaOld = legacyJackson2Serializer().deserialize(old);

		assertEquals("com.zhiflow.common.core.util.R", viaNew.getClass().getName());
		assertArrayEquals(legacyJackson2Serializer().serialize(viaOld),
				legacyJackson2Serializer().serialize(viaNew), "字典缓存跨版本读出的内容必须一致");
	}

	@Test
	@DisplayName("登录态双向兼容：旧 jar 写的 BaseUser 新 jar 读得回，反之亦然（滚动发布期间两版同时在线）")
	void baseUserCrossVersionRoundTrip() {
		BaseUser user = new BaseUser("1000000000000000004", "1", "-1", "-1",
				"system", "{bcrypt}$2a$10$x", List.of("1", "-1"),
				true, true, true, true,
				AuthorityUtils.createAuthorityList("ROLE_ADMIN", "sys_user_add"));

		Object newReadsOld = jackson3Serializer().deserialize(legacyJackson2Serializer().serialize(user));
		assertInstanceOf(BaseUser.class, newReadsOld, "新 jar 读旧 jar 写的登录态，得到 "
				+ (newReadsOld == null ? "null" : newReadsOld.getClass().getName()));
		assertEquals("system", ((BaseUser) newReadsOld).getUsername());
		assertEquals(2, ((BaseUser) newReadsOld).getAuthorities().size(), "authorities 丢了等于权限全没了");

		Object oldReadsNew = legacyJackson2Serializer().deserialize(jackson3Serializer().serialize(user));
		assertInstanceOf(BaseUser.class, oldReadsNew, "旧 jar 读新 jar 写的登录态，得到 "
				+ (oldReadsNew == null ? "null" : oldReadsNew.getClass().getName()));
		assertEquals("1000000000000000004", ((BaseUser) oldReadsNew).getId());
	}

	@Test
	@DisplayName("滚动发布不丢数据：真实菜单树经「新写→旧读」「旧写→新读」两条路，内容都不变")
	void rollingDeployLosesNothingOnRealData() throws Exception {
		byte[] old = fixture("public_menu_cache__1_menu.json");
		Object baseline = legacyJackson2Serializer().deserialize(old);
		byte[] normalized = legacyJackson2Serializer().serialize(baseline);

		Object newWritesOldReads = legacyJackson2Serializer()
				.deserialize(jackson3Serializer().serialize(jackson3Serializer().deserialize(old)));
		assertArrayEquals(normalized, legacyJackson2Serializer().serialize(newWritesOldReads),
				"旧 jar 读新 jar 写的缓存值时丢了数据 ⇒ 滚动发布期间会出问题");

		Object oldWritesNewReads = jackson3Serializer().deserialize(normalized);
		assertArrayEquals(normalized, legacyJackson2Serializer().serialize(oldWritesNewReads),
				"新 jar 读旧 jar 写的缓存值时丢了数据");
	}

	@Test
	@DisplayName("已知副作用：迁到 Jackson 3 后 @Sensitive 在缓存层重新生效，租户手机号回到打码存储")
	void jackson3CacheReactivatesSensitiveMasking() {
		SysTenant tenant = new SysTenant();
		tenant.setId("1");
		tenant.setName("测试租户");
		tenant.setPhone("13800137999");

		String byLegacy = new String(legacyJackson2Serializer().serialize(tenant), StandardCharsets.UTF_8);
		String byNew = new String(jackson3Serializer().serialize(tenant), StandardCharsets.UTF_8);

		// @Sensitive 的序列化器 SensitiveSerialize 继承的是 Jackson 3 的 ValueSerializer，
		// 所以它只对 Jackson 3 的 mapper 生效。迁移前缓存 mapper 是 Jackson 2 ⇒ 存明文；
		// 迁移后 ⇒ 存打码值，等于回到 2026-09-12 Jackson 3 接口层上线之前的长期行为。
		// 这条测试不是"要求"哪种行为，是把这个副作用钉成显式约定，免得下次有人当成 bug 查半天。
		assertTrue(byLegacy.contains("\"phone\":\"13800137999\""),
				"迁移前（Jackson 2 缓存）应存明文，实际=" + byLegacy);
		assertFalse(byNew.contains("\"phone\":\"13800137999\""),
				"迁移后（Jackson 3 缓存）应存打码值，实际=" + byNew);
		assertTrue(byNew.contains("\"phone\":\"*******7999\""), "打码形态应为 *******7999，实际=" + byNew);
	}
}
