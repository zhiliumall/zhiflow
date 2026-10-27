package com.zhiflow.common.lock.idempotent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 幂等切面状态机单测。
 *
 * <p>重点在<b>降级路径</b>：幂等是防重不是鉴权，Redis 抖一下不该让退款/短信全部 500。
 * 这类分支线上极少触发，一旦触发就是事故，只能靠单测钉住。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdempotentAspectTest {

	private static final String TOKEN = "req-uuid-1";

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOps;

	@Mock
	private ProceedingJoinPoint point;

	private final ObjectMapper objectMapper = new JsonMapper();

	private IdempotentProperties properties;

	private IdempotentAspect aspect;

	@BeforeEach
	void setUp() {
		properties = new IdempotentProperties();
		aspect = new IdempotentAspect(redisTemplate, properties, objectMapper);
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		withHeader(TOKEN);
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
	}

	// ---------- 正常路径 ----------

	@Test
	@DisplayName("首次请求：抢到坑位 → 执行业务 → 缓存响应")
	void firstRequestExecutesAndCaches() throws Throwable {
		when(valueOps.setIfAbsent(anyString(), eq(IdempotentAspect.PROCESSING), any(Duration.class)))
				.thenReturn(true);
		when(redisTemplate.getExpire(anyString())).thenReturn(55L);
		when(point.proceed()).thenReturn(new Result("ok"));

		Object result = aspect.around(point, annotation());

		assertEquals("ok", ((Result) result).getValue());
		verify(point).proceed();
		// 缓存时保留剩余 TTL（55），而不是重置成 60 —— 否则慢请求会把窗口无限延长
		verify(valueOps).set(anyString(), anyString(), eq(Duration.ofSeconds(55)));
	}

	@Test
	@DisplayName("重复请求：命中缓存 → 不执行业务 → 原样重放首次响应")
	void duplicateRequestReplaysCachedResponse() throws Throwable {
		when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
		when(valueOps.get(anyString())).thenReturn(objectMapper.writeValueAsString(
				new IdempotentResponse(Result.class.getName(),
						objectMapper.writeValueAsString(new Result("首次的响应")))));

		Object result = aspect.around(point, annotation());

		assertEquals("首次的响应", ((Result) result).getValue());
		verify(point, never()).proceed();
	}

	/** 第二个请求到达时第一个还没跑完，此时并不存在可返回的响应 */
	@Test
	@DisplayName("首次仍在处理中：明确提示，而不是脏读或空响应")
	void concurrentRequestGetsProcessingHint() throws Throwable {
		when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
		when(valueOps.get(anyString())).thenReturn(IdempotentAspect.PROCESSING);

		IdempotentException e = assertThrows(IdempotentException.class, () -> aspect.around(point, annotation()));

		assertTrue(e.getMessage().contains("仍在处理中"), e.getMessage());
		verify(point, never()).proceed();
	}

	/** 失败的操作重放没有防重价值，用户应能立刻重试 */
	@Test
	@DisplayName("业务抛异常：删 key 放行重试，异常原样上抛")
	void businessExceptionReleasesKey() throws Throwable {
		when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
		when(point.proceed()).thenThrow(new IllegalStateException("库存不足"));

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> aspect.around(point, annotation()));

		assertEquals("库存不足", e.getMessage());
		verify(redisTemplate).delete(anyString());
	}

	// ---------- 降级路径（本类重点） ----------

	@Test
	@DisplayName("Redis 故障 → 降级放行，业务照常执行")
	void redisFailureDegradesToPassThrough() throws Throwable {
		when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
				.thenThrow(new RuntimeException("valkey 连接超时"));
		when(point.proceed()).thenReturn(new Result("业务照常"));

		Object result = aspect.around(point, annotation());

		assertEquals("业务照常", ((Result) result).getValue());
		verify(point).proceed();
	}

	@Test
	@DisplayName("无 Idempotency-Key 且未强制 → 放行（前端拦截器上线前的默认路径）")
	void missingHeaderPassesThroughByDefault() throws Throwable {
		withHeader(null);
		when(point.proceed()).thenReturn(new Result("放行"));

		Object result = aspect.around(point, annotation());

		assertEquals("放行", ((Result) result).getValue());
		verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
	}

	@Test
	@DisplayName("无 key 且端点声明 required=YES → 拒绝")
	void missingHeaderRejectedWhenRequired() {
		withHeader(null);

		assertThrows(IdempotentException.class,
				() -> aspect.around(point, annotation(Idempotent.Required.YES)));
	}

	@Test
	@DisplayName("全局开关关闭 → 完全不介入")
	void disabledSkipsEverything() throws Throwable {
		properties.setEnabled(false);
		when(point.proceed()).thenReturn(new Result("不介入"));

		aspect.around(point, annotation());

		verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
	}

	@Test
	@DisplayName("缓存内容损坏无法反序列化 → 降级为提示，不抛看不懂的异常")
	void corruptedCacheDegradesToHint() {
		when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
		when(valueOps.get(anyString())).thenReturn("{不是合法 JSON");

		IdempotentException e = assertThrows(IdempotentException.class, () -> aspect.around(point, annotation()));

		assertInstanceOf(IdempotentException.class, e);
	}

	// ---------- helpers ----------

	private void withHeader(String token) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (token != null) {
			request.addHeader(IdempotentAspect.HEADER, token);
		}
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

	private Idempotent annotation() {
		return annotation(Idempotent.Required.FOLLOW_GLOBAL);
	}

	private Idempotent annotation(Idempotent.Required required) {
		return new Idempotent() {
			@Override
			public Class<? extends java.lang.annotation.Annotation> annotationType() {
				return Idempotent.class;
			}

			@Override
			public Required required() {
				return required;
			}

			@Override
			public int ttlSeconds() {
				return 0;
			}
		};
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	static class Result {

		private String value;
	}
}
