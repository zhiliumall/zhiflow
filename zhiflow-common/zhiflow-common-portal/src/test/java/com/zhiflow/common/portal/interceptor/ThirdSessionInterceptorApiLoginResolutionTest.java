package com.zhiflow.common.portal.interceptor;

import com.zhiflow.common.portal.annotation.ApiLogin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@code ThirdSessionInterceptor} 解析 {@code @ApiLogin} 的口径：<b>方法级优先、类级回退</b>。
 *
 * <h2>为什么这几行值得单独测</h2>
 * <p>改造前只读 {@code method.getMethodAnnotation(ApiLogin.class)}，<b>类级 {@code @ApiLogin}
 * 运行期一行都不生效</b>——注解写着 {@code mustLogin = true}、行为却是放行，而且本地怎么点都
 * 看不出来（类上有注解，读代码的人会以为拦住了）。同时架构门禁 R1b 的判据里<b>认</b>类级注解，
 * 两边一叠加就是「门禁绿、运行期无鉴权」：{@code POST /mallapi/file/upload} 匿名可往 OSS 传文件
 * 就是这么活下来的（2026-08-17 实测）。
 *
 * <p>下面第 2 条是<b>「净效果为零」的判据</b>：全仓 48 处类级注解里 47 处是
 * {@code mustLogin = false}，回退取到它之后必须仍然放行，否则这次改动会把大半个 C 端锁死。
 */
class ThirdSessionInterceptorApiLoginResolutionTest {

	private ThirdSessionInterceptor interceptor;
	private MockHttpServletRequest request;
	private MockHttpServletResponse response;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		// 两个依赖在本测试覆盖的分支里都用不到：没有 third-session 头就不查 Redis，
		interceptor = new ThirdSessionInterceptor(mock(RedisTemplate.class));
		request = new MockHttpServletRequest();
		request.addHeader("client-type", "H5");
		request.addHeader("tenant-id", "1");
		response = new MockHttpServletResponse();
	}

	private boolean preHandle(Object controller, String methodName) throws Exception {
		Method method = controller.getClass().getDeclaredMethod(methodName);
		return interceptor.preHandle(request, response, new HandlerMethod(controller, method));
	}

	/** 被拦下时的判据是 body 里的 60002，不是 HTTP 状态码——C 端鉴权失败一律回 200 */
	private void assertBlockedWith60002() throws java.io.UnsupportedEncodingException {
		assertEquals(200, response.getStatus(), "C 端鉴权拒绝走的是 HTTP 200 + 业务码，不是 4xx");
		assertTrue(response.getContentAsString().contains("60002"),
				"应回 60002「session不能为空」，实际：" + response.getContentAsString());
	}

	@Test
	@DisplayName("类级 mustLogin=true + 方法无注解 → 拦下（改造前这里是放行，即裸奔）")
	void classLevelMustLogin_methodUnannotated_isBlocked() throws Exception {
		assertFalse(preHandle(new ClassLevelRequired(), "unannotated"));
		assertBlockedWith60002();
	}

	@Test
	@DisplayName("类级 mustLogin=false + 方法无注解 → 放行（净效果为零的判据，47/48 处属此形）")
	void classLevelOptional_methodUnannotated_isAllowed() throws Exception {
		assertTrue(preHandle(new ClassLevelOptional(), "unannotated"));
	}

	@Test
	@DisplayName("方法级优先：类级 false 但方法级要求登录 → 拦下")
	void methodLevelOverridesClassLevelOptional() throws Exception {
		assertFalse(preHandle(new ClassLevelOptional(), "required"));
		assertBlockedWith60002();
	}

	@Test
	@DisplayName("方法级优先：类级 true 但方法级 mustLogin=false → 放行")
	void methodLevelOverridesClassLevelRequired() throws Exception {
		assertTrue(preHandle(new ClassLevelRequired(), "optional"));
	}

	@Test
	@DisplayName("类级与方法级都没有 → 放行（与改造前一致）")
	void noAnnotationAtAll_isAllowed() throws Exception {
		assertTrue(preHandle(new NoAnnotation(), "unannotated"));
	}

	// ---------------------------------------------------------------- 被测样板

	@ApiLogin(mustLogin = true)
	static class ClassLevelRequired {
		public void unannotated() {
		}

		@ApiLogin(mustLogin = false)
		public void optional() {
		}
	}

	@ApiLogin(mustLogin = false)
	static class ClassLevelOptional {
		public void unannotated() {
		}

		@ApiLogin
		public void required() {
		}
	}

	static class NoAnnotation {
		public void unannotated() {
		}
	}
}
