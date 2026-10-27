package com.zhiflow.common.lock.ratelimit;

import com.zhiflow.common.core.exception.TooManyRequestsException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code @RateLimit}：超限抛 429 异常且不进业务方法；配置覆盖注解阈值；按 IP / 用户分桶；Redis 出错放行。
 * 只 mock Redisson 的接口（RedissonClient / RRateLimiter）。
 */
class RateLimitAspectTest {

	private final RedissonClient redisson = mock(RedissonClient.class);

	private final RRateLimiter limiter = mock(RRateLimiter.class);

	private final RateLimitProperties properties = new RateLimitProperties();

	private final StaticListableBeanFactory beans = new StaticListableBeanFactory();

	private RateLimitAspect aspect;

	static class Samples {

		@RateLimit(name = "login", permits = 3, periodSeconds = 60, message = "登录太频繁")
		void login() {
		}

		@RateLimit(name = "order-create", by = RateLimit.Dimension.USER, permits = 5, periodSeconds = 30)
		void order() {
		}
	}

	@BeforeEach
	void setUp() {
		when(redisson.getRateLimiter(anyString())).thenReturn(limiter);
		aspect = new RateLimitAspect(redisson, properties, beans.getBeanProvider(RateLimitPrincipalResolver.class));
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("203.0.113.9");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	@DisplayName("放行：按 IP 分桶、用注解阈值，业务方法照常执行")
	void allowsWithinLimit() throws Throwable {
		when(limiter.tryAcquire()).thenReturn(true);
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn("ok");

		assertEquals("ok", aspect.around(pjp, annotation("login")));

		verify(redisson).getRateLimiter("ratelimit:login:ip:203.0.113.9");
		verify(limiter).trySetRate(RateType.OVERALL, 3, Duration.ofSeconds(60), Duration.ofSeconds(120));
	}

	@Test
	@DisplayName("超限：抛 TooManyRequestsException（429），不进业务方法")
	void rejectsOverLimit() throws Throwable {
		when(limiter.tryAcquire()).thenReturn(false);
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

		TooManyRequestsException e = assertThrows(TooManyRequestsException.class,
				() -> aspect.around(pjp, annotation("login")));

		assertEquals("登录太频繁", e.getMessage());
		verify(pjp, never()).proceed();
	}

	@Test
	@DisplayName("配置按规则名覆盖注解阈值")
	void configOverridesAnnotation() {
		RateLimitProperties.Rule rule = new RateLimitProperties.Rule();
		rule.setPermits(50L);
		properties.getRules().put("login", rule);
		when(limiter.tryAcquire()).thenReturn(true);

		aspect.tryAcquire(annotation("login"));

		verify(limiter).trySetRate(RateType.OVERALL, 50, Duration.ofSeconds(60), Duration.ofSeconds(120));
	}

	@Test
	@DisplayName("按用户：有登录用户按用户分桶，没有就退回按 IP")
	void userDimensionFallsBackToIp() {
		when(limiter.tryAcquire()).thenReturn(true);
		aspect.tryAcquire(annotation("order"));
		verify(redisson).getRateLimiter("ratelimit:order-create:ip:203.0.113.9");

		beans.addBean("principal", (RateLimitPrincipalResolver) () -> "u42");
		aspect.tryAcquire(annotation("order"));
		verify(redisson).getRateLimiter("ratelimit:order-create:user:u42");
	}

	@Test
	@DisplayName("Redis 出错放行（限流是保护，不是准入）；总开关关掉不查 Redis")
	void failsOpenAndCanBeDisabled() throws Throwable {
		when(limiter.trySetRate(any(), anyLong(), any(Duration.class), any(Duration.class)))
				.thenThrow(new IllegalStateException("redis down"));
		ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
		when(pjp.proceed()).thenReturn("ok");
		assertEquals("ok", aspect.around(pjp, annotation("login")));

		RedissonClient untouched = mock(RedissonClient.class);
		properties.setEnabled(false);
		RateLimitAspect off = new RateLimitAspect(untouched, properties, beans.getBeanProvider(RateLimitPrincipalResolver.class));
		assertEquals("ok", off.around(pjp, annotation("login")));
		verify(untouched, never()).getRateLimiter(eq("ratelimit:login:ip:203.0.113.9"));
	}

	private static RateLimit annotation(String method) {
		try {
			Method m = Samples.class.getDeclaredMethod(method);
			return m.getAnnotation(RateLimit.class);
		}
		catch (NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}
}
