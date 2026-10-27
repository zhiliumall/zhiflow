package com.zhiflow.common.data.cache;

import com.zhiflow.common.core.util.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link R} 经 Valkey 缓存序列化器<b>往返</b>的护栏。
 *
 * <h2>为什么需要这个测试</h2>
 *
 * <p>2026-08-20 查实：{@code R} 只有 4 个字段，但 {@code isOk()} 被 Jackson 当派生 getter
 * ⇒ 写出去带第 5 个属性 {@code ok}；而
 * {@link RedisTemplateConfig#createValkeyOptimizedJsonSerializer()} 是<b>手搓的
 * {@code new ObjectMapper()}</b>，没关 {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * ⇒ 读回来不认 {@code ok} 直接抛。
 *
 * <p>后果是**沉默的**：{@code DefensiveRedisCache} 捕获异常、删脏 key、回源，
 * 所以<b>功能一直正确，只是缓存恒为 0 命中</b>。唯一的信号是一行 WARN
 * （upms 近 24h ≥25 次）。没有这个测试，这类缺陷只能靠翻日志偶然发现。
 *
 * <h2>判据为什么是「往返」而不是「能序列化」</h2>
 *
 * <p>只测序列化会永远绿——出问题的从来是读回那一侧。必须 write→read 走完整圈。
 *
 * <h2>⚠️ 改这个测试前先跑对照组</h2>
 *
 * <p>把 {@code R} 类上的 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 注释掉，
 * 本测试**必须变红**。不变红说明判据是坏的（比如序列化器没真用上那个 mapper），
 * 此时「测试通过」不代表任何事。
 */
class RSerializationRoundTripTest {

	private final GenericJacksonJsonRedisSerializer serializer =
			RedisTemplateConfig.createValkeyOptimizedJsonSerializer();

	@Test
	@DisplayName("R.ok(data) 往返：不抛异常且字段逐个相等")
	void okRoundTrips() {
		R<String> original = R.ok("payload", "成功");

		byte[] bytes = serializer.serialize(original);
		assertNotNull(bytes);

		// 先确认「脏」确实存在——否则这个测试可能是在验一个根本不会发生的场景
		String json = new String(bytes);
		assertTrue(json.contains("\"ok\""),
				"前提失效：序列化结果里没有 ok 字段，说明 isOk() 不再被当 getter，"
						+ "本测试守护的场景已不存在，应重新评估而不是直接删注解。json=" + json);

		Object restored = assertDoesNotThrow(() -> serializer.deserialize(bytes),
				"R 读不回来 ⇒ 所有以 R 为值的缓存命中率为 0");

		assertNotNull(restored);
		assertTrue(restored instanceof R, "反序列化后应仍是 R，实际=" + restored.getClass());

		@SuppressWarnings("unchecked")
		R<String> back = (R<String>) restored;
		assertEquals(original.getCode(), back.getCode());
		assertEquals(original.getMsg(), back.getMsg());
		assertEquals(original.getData(), back.getData());
		assertEquals(original.getErrCode(), back.getErrCode());
		// ok 是派生值，不是存储字段：读回后应由 code 重新算出，而不是从 JSON 读
		assertEquals(original.isOk(), back.isOk());
	}

	@Test
	@DisplayName("R.failed(...) 往返：失败态的 code/msg 不被吞掉")
	void failedRoundTrips() {
		R<String> original = R.failed("业务失败");

		Object restored = assertDoesNotThrow(() -> serializer.deserialize(serializer.serialize(original)));

		@SuppressWarnings("unchecked")
		R<String> back = (R<String>) restored;
		assertEquals(original.getCode(), back.getCode());
		assertEquals("业务失败", back.getMsg());
		assertEquals(Boolean.FALSE, back.isOk());
	}

	/**
	 * 实际炸的就是这两种形状：{@code dict_cache} 的值是
	 * {@code R<List<...>>}（{@code authorized_grant_types}）与 {@code R<Map<...>>}（语系包）。
	 * 泛型容器多一层嵌套，{@code activateDefaultTyping} 的类型信息也多一层，值得单独钉住。
	 */
	@Test
	@DisplayName("R 包着集合/Map 时同样能往返（dict_cache 的真实形状）")
	void collectionPayloadRoundTrips() {
		R<List<String>> listResult = R.ok(List.of("authorization_code", "refresh_token"));
		Object restoredList = assertDoesNotThrow(
				() -> serializer.deserialize(serializer.serialize(listResult)));
		@SuppressWarnings("unchecked")
		R<List<String>> backList = (R<List<String>>) restoredList;
		assertEquals(List.of("authorization_code", "refresh_token"), backList.getData());

		R<Map<String, String>> mapResult = R.ok(Map.of("commons.tip", "提示"));
		Object restoredMap = assertDoesNotThrow(
				() -> serializer.deserialize(serializer.serialize(mapResult)));
		@SuppressWarnings("unchecked")
		R<Map<String, String>> backMap = (R<Map<String, String>>) restoredMap;
		assertEquals("提示", backMap.getData().get("commons.tip"));
	}
}
