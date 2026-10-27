package com.zhiflow.common.portal.interceptor;

import cn.hutool.json.JSONUtil;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.common.portal.entity.ThirdSession;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 可选登录接口挂登录态前，要按必须登录分支同一条规则判断「这份会话属不属于本次请求」。
 *
 * <p>开源分支租户固定为默认租户 1，{@code tenant-id} 头不再参与：会话必须属于默认租户，
 * {@code app-id} 头与会话 appId 都非空时必须相等。可选登录接口不匹配时按匿名处理（不回 60001、不拦请求，
 * 只是不挂那份会话）；必须登录接口不匹配回 60001。
 */
class ThirdSessionInterceptorOptionalSessionTenantTest {

	private static final String TOKEN = "session-token-for-test";

	private ThirdSessionInterceptor interceptor;
	private ValueOperations<String, Object> valueOps;
	private MockHttpServletRequest request;
	private MockHttpServletResponse response;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
		valueOps = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		// 用真实默认值：带 third-session 头时会先判「是不是 mock 前缀」，mock 出来的 null 前缀会空指针
		interceptor = new ThirdSessionInterceptor(redisTemplate);
		request = new MockHttpServletRequest();
		response = new MockHttpServletResponse();
	}

	@AfterEach
	void tearDown() {
		ThirdSessionHolder.clear();
		TenantContextHolder.clear();
	}

	static class Endpoints {
		@ApiLogin(mustLogin = false)
		public void optional() {
		}

		public void unannotated() {
		}

		@ApiLogin
		public void required() {
		}
	}

	private boolean preHandle(String methodName) throws Exception {
		Endpoints controller = new Endpoints();
		return interceptor.preHandle(request, response,
				new HandlerMethod(controller, Endpoints.class.getDeclaredMethod(methodName)));
	}

	/** Redis 里放一份会话；issuedAt 设为当前，避免走「补写签发时间」的 set 分支。 */
	private void sessionInRedis(String tenantId, String appId, String userId) {
		ThirdSession s = new ThirdSession();
		s.setTenantId(tenantId);
		s.setAppId(appId);
		s.setUserId(userId);
		s.setIssuedAt(System.currentTimeMillis());
		when(valueOps.getAndExpire(eq(ThirdSessionInterceptor.getSessionRedisKey(TOKEN)), anyLong(), eq(TimeUnit.HOURS)))
				.thenReturn(JSONUtil.toJsonStr(s));
		request.addHeader("third-session", TOKEN);
	}

	// ---------------- H5 / PC / APP 分支 ----------------

	@Test
	@DisplayName("H5 可选登录：默认租户的会话照常挂上；伪造的 tenant-id 头不影响租户")
	void h5_defaultTenantSession_attached() throws Exception {
		request.addHeader("client-type", "H5");
		request.addHeader("tenant-id", "B");
		sessionInRedis(CommonConstants.DEFAULT_TENANT_ID, null, "user-1");

		assertTrue(preHandle("optional"));
		assertEquals("user-1", ThirdSessionHolder.getUserId());
		assertEquals(CommonConstants.DEFAULT_TENANT_ID, TenantContextHolder.getTenantId());
	}

	@Test
	@DisplayName("H5 可选登录：别的租户签发的会话 → 放行但不挂会话（按匿名处理），租户仍是默认租户")
	void h5_foreignTenantSession_dropped() throws Exception {
		request.addHeader("client-type", "H5");
		sessionInRedis("A", null, "user-of-A");

		assertTrue(preHandle("optional"));
		assertNull(ThirdSessionHolder.getThirdSession(), "别的租户的会话不能挂到这次请求上");
		assertEquals(CommonConstants.DEFAULT_TENANT_ID, TenantContextHolder.getTenantId());
		assertEquals("", response.getContentAsString(), "可选接口不回 60001，匿名能看的照样能看");
	}

	@Test
	@DisplayName("H5 无注解（等同可选登录）同样生效")
	void h5_unannotated_foreignTenantSession_dropped() throws Exception {
		request.addHeader("client-type", "H5");
		sessionInRedis("A", null, "user-of-A");

		assertTrue(preHandle("unannotated"));
		assertNull(ThirdSessionHolder.getThirdSession());
		assertEquals(CommonConstants.DEFAULT_TENANT_ID, TenantContextHolder.getTenantId());
	}

	@Test
	@DisplayName("H5 匿名：不带 tenant-id 头也放行（原来回 60004），租户是默认租户")
	void h5_anonymous_noTenantHeaderNeeded() throws Exception {
		request.addHeader("client-type", "H5");

		assertTrue(preHandle("optional"));
		assertNull(ThirdSessionHolder.getThirdSession());
		assertEquals(CommonConstants.DEFAULT_TENANT_ID, TenantContextHolder.getTenantId());
		assertEquals("", response.getContentAsString());
	}

	// ---------------- 小程序 / 带 appId 的公众号 H5 分支 ----------------

	@Test
	@DisplayName("小程序可选登录：默认租户且 appId 匹配 → 挂上")
	void ma_match_sessionAttached() throws Exception {
		request.addHeader("client-type", "MA");
		request.addHeader("app-id", "wx-app");
		sessionInRedis(CommonConstants.DEFAULT_TENANT_ID, "wx-app", "user-1");

		assertTrue(preHandle("optional"));
		assertEquals("user-1", ThirdSessionHolder.getUserId());
		assertEquals(CommonConstants.DEFAULT_TENANT_ID, TenantContextHolder.getTenantId());
	}

	@Test
	@DisplayName("小程序可选登录：app-id 头与会话 appId 不同 → 不挂会话（与必须登录分支同一条判据）")
	void ma_appIdMismatch_sessionDropped() throws Exception {
		request.addHeader("client-type", "MA");
		request.addHeader("app-id", "wx-other");
		sessionInRedis(CommonConstants.DEFAULT_TENANT_ID, "wx-app", "user-1");

		assertTrue(preHandle("optional"));
		assertNull(ThirdSessionHolder.getThirdSession());
		assertEquals(CommonConstants.DEFAULT_TENANT_ID, TenantContextHolder.getTenantId());
	}

	@Test
	@DisplayName("小程序匿名：租户是默认租户")
	void ma_anonymous_defaultTenant() throws Exception {
		request.addHeader("client-type", "MA");
		request.addHeader("tenant-id", "B");

		assertTrue(preHandle("optional"));
		assertNull(ThirdSessionHolder.getThirdSession());
		assertEquals(CommonConstants.DEFAULT_TENANT_ID, TenantContextHolder.getTenantId());
	}

	// ---------------- 必须登录分支 ----------------

	@Test
	@DisplayName("必须登录：别的租户签发的会话 → 60001")
	void required_foreignTenantSession_60001() throws Exception {
		request.addHeader("client-type", "H5");
		sessionInRedis("A", null, "user-of-A");

		assertFalse(preHandle("required"));
		assertTrue(response.getContentAsString().contains("60001"), response.getContentAsString());
		assertNull(ThirdSessionHolder.getThirdSession());
	}

	@Test
	@DisplayName("必须登录：默认租户的会话 → 放行并挂会话")
	void required_defaultTenantSession_attached() throws Exception {
		request.addHeader("client-type", "H5");
		sessionInRedis(CommonConstants.DEFAULT_TENANT_ID, null, "user-1");

		assertTrue(preHandle("required"));
		assertEquals("user-1", ThirdSessionHolder.getUserId());
		assertEquals(CommonConstants.DEFAULT_TENANT_ID, TenantContextHolder.getTenantId());
	}

	@Test
	@DisplayName("必须登录：app-id 不匹配 → 60001")
	void required_appIdMismatch_60001() throws Exception {
		request.addHeader("client-type", "MA");
		request.addHeader("app-id", "wx-other");
		sessionInRedis(CommonConstants.DEFAULT_TENANT_ID, "wx-app", "user-1");

		assertFalse(preHandle("required"));
		assertTrue(response.getContentAsString().contains("60001"), response.getContentAsString());
	}

	@Test
	@DisplayName("必须登录：会话里没有租户 → 60001（原来这里空指针落 500）")
	void required_sessionWithoutTenant_is60001NotNpe() throws Exception {
		request.addHeader("client-type", "H5");
		when(valueOps.getAndExpire(anyString(), anyLong(), eq(TimeUnit.HOURS)))
				.thenReturn("{\"userId\":\"u\",\"issuedAt\":" + System.currentTimeMillis() + "}");
		request.addHeader("third-session", TOKEN);

		assertFalse(preHandle("required"));
		assertTrue(response.getContentAsString().contains("60001"), response.getContentAsString());
	}
}
