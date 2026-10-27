package com.zhiflow.common.data.tenant;

import com.zhiflow.common.core.constant.CommonConstants;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code TenantContextHolderFilter} 进出都要清租户。
 *
 * <p>Tomcat 平台线程是复用的：上一个请求异常退出时没清的租户，会被这条线程接的下一个请求读到。
 * 开源分支租户固定为默认租户、不读请求头：token 解析不出租户时落默认租户，既不沿用残留值，也不听 tenant-id 头。
 */
class TenantContextHolderFilterCleanupTest {

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	@Test
	@DisplayName("链路抛异常，租户也要清掉")
	void clearsTenantWhenChainThrows() {
		TenantContextHolderFilter filter = new TenantContextHolderFilter(emptyRedis(), null);
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("tenant-id", "tenant-B");
		FilterChain failing = (req, res) -> {
			assertEquals(CommonConstants.DEFAULT_TENANT_ID, TenantContextHolder.getTenantId(), "tenant-id 头不参与租户解析");
			throw new IllegalStateException("业务异常");
		};

		assertThrows(IllegalStateException.class,
				() -> filter.doFilter(request, new MockHttpServletResponse(), failing));
		assertNull(TenantContextHolder.getTenantId(), "异常退出后线程上不能留租户");
	}

	@Test
	@DisplayName("线程上残留旧租户时，token 失效的请求落默认租户，既不沿用旧租户、也不听 tenant-id 头")
	void staleTenantOnReusedThreadIsNotInherited() throws Exception {
		TenantContextHolder.setTenantId("stale-A");
		TenantContextHolderFilter filter = new TenantContextHolderFilter(emptyRedis(), null);
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer expired-token");
		request.addHeader("tenant-id", "tenant-B");
		AtomicReference<String> seen = new AtomicReference<>();

		filter.doFilter(request, new MockHttpServletResponse(),
				(req, res) -> seen.set(TenantContextHolder.getTenantId()));

		assertEquals(CommonConstants.DEFAULT_TENANT_ID, seen.get());
		assertNull(TenantContextHolder.getTenantId());
	}

	/** Redis 里查不到任何授权记录（token 过期 / 无效）。 */
	@SuppressWarnings("unchecked")
	private static RedisTemplate<String, Object> emptyRedis() {
		ValueOperations<String, Object> ops = (ValueOperations<String, Object>) Proxy.newProxyInstance(
				ValueOperations.class.getClassLoader(), new Class<?>[] { ValueOperations.class },
				(proxy, method, args) -> null);
		return new RedisTemplate<>() {
			@Override
			public ValueOperations<String, Object> opsForValue() {
				return ops;
			}

			@Override
			public Set<String> keys(String pattern) {
				return Set.of();
			}
		};
	}

}
