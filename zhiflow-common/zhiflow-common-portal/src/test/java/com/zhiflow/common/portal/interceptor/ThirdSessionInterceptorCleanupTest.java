package com.zhiflow.common.portal.interceptor;

import com.zhiflow.common.portal.entity.ThirdSession;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.common.security.annotation.Inside;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * C 端登录态要在<b>请求结束</b>时清掉，而不是等下一个请求的 preHandle。
 *
 * <p>{@code @Inside(true)} 内部接口在 preHandle 里直接放行、走不到任何清理分支 ——
 * 平台线程复用时，上一个 C 端请求的登录态会一直挂在这条线程上（7/21「匿名请求继承上一请求残留登录态」同源）。
 */
class ThirdSessionInterceptorCleanupTest {

	private final ThirdSessionInterceptor interceptor =
			new ThirdSessionInterceptor(mock(RedisTemplate.class));

	@AfterEach
	void tearDown() {
		ThirdSessionHolder.clear();
	}

	static class InsideController {
		@Inside
		public void internalOnly() {
		}
	}

	@Test
	@DisplayName("@Inside 接口不解析也不清，请求结束时 afterCompletion 清掉残留登录态")
	void afterCompletionClearsSessionLeftByPreviousRequest() throws Exception {
		ThirdSessionHolder.setThirdSession(session("user-from-previous-request"));
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		HandlerMethod handler = new HandlerMethod(new InsideController(),
				InsideController.class.getDeclaredMethod("internalOnly"));

		assertTrue(interceptor.preHandle(request, response, handler));
		assertNotNull(ThirdSessionHolder.getThirdSession(), "前提：@Inside 放行路径本身不清（这正是要兜住的洞）");

		interceptor.afterCompletion(request, response, handler, null);

		assertNull(ThirdSessionHolder.getThirdSession());
	}

	@Test
	@DisplayName("异步请求：原线程交还容器前清掉登录态")
	void afterConcurrentHandlingStartedClearsSession() {
		ThirdSessionHolder.setThirdSession(session("user-1"));

		interceptor.afterConcurrentHandlingStarted(new MockHttpServletRequest(), new MockHttpServletResponse(),
				new Object());

		assertNull(ThirdSessionHolder.getThirdSession());
	}

	private static ThirdSession session(String userId) {
		ThirdSession session = new ThirdSession();
		session.setUserId(userId);
		session.setTenantId("1");
		return session;
	}

}
