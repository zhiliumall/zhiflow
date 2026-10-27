package com.zhiflow.common.log.aspect;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.log.config.BaseLogProperties;
import com.zhiflow.common.log.event.SysLogEvent;
import com.zhiflow.common.security.internal.InternalCallContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计切面回归。
 *
 * <p>核心保护的是「失败也要留痕」：改造前 {@code point.proceed()} 裸调，异常一抛
 * {@code publishEvent} 永不执行，于是失败操作在 sys_log 里完全不存在——审计里
 * 长期一条 ERROR 记录都没有就是这么来的。
 *
 * <p>刻意不用 {@code mockStatic}（本机 JDK 25 下 Mockito 插桩有存量问题），改为
 * 提供真实的 {@code RequestContextHolder} 上下文让 {@code SysLogUtils} 真实执行。
 */
class SysLogAspectTest {

	private ApplicationEventPublisher publisher;

	private BaseLogProperties properties;

	private SysLogAspect aspect;

	private MockHttpServletRequest request;

	@BeforeEach
	void setUp() {
		publisher = mock(ApplicationEventPublisher.class);
		properties = new BaseLogProperties();
		properties.setExcludeFields(List.of("password", "mobile", "idcard", "phone"));
		aspect = new SysLogAspect(publisher, properties);

		request = new MockHttpServletRequest("DELETE", "/goods/spu/123");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	@DisplayName("成功路径：type=0，不带异常信息")
	void successPathRecordsNormalType() throws Throwable {
		ProceedingJoinPoint point = pointFor("removeById", new Object[] { "123" });
		when(point.proceed()).thenReturn("ok");

		Object result = aspect.around(point, sysLogAnnotation("删除商品"));

		assertEquals("ok", result);
		com.zhiflow.upms.common.dto.SysLogPayload logged = capturePublished();
		assertEquals(CommonConstants.LOG_TYPE_0, logged.getType());
		assertEquals("删除商品", logged.getTitle());
		assertNull(logged.getException());
		assertNotNull(logged.getTime());
	}

	@Test
	@DisplayName("失败路径：仍然留痕，type=9 且带异常摘要")
	void failurePathIsStillRecorded() throws Throwable {
		ProceedingJoinPoint point = pointFor("removeById", new Object[] { "123" });
		IllegalStateException boom = new IllegalStateException("库存已锁定");
		when(point.proceed()).thenThrow(boom);

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> aspect.around(point, sysLogAnnotation("删除商品")));

		// 异常必须原样重抛：审计改造不得改变业务错误语义
		assertSame(boom, thrown);

		com.zhiflow.upms.common.dto.SysLogPayload logged = capturePublished();
		assertEquals(CommonConstants.LOG_TYPE_9, logged.getType());
		assertTrue(logged.getException().contains("IllegalStateException"));
		assertTrue(logged.getException().contains("库存已锁定"));
	}

	@Test
	@DisplayName("失败路径：异常摘要含根因")
	void failureSummaryIncludesRootCause() throws Throwable {
		ProceedingJoinPoint point = pointFor("removeById", new Object[] { "123" });
		when(point.proceed()).thenThrow(
				new RuntimeException("包装层", new IllegalArgumentException("真正原因：id 非法")));

		assertThrows(RuntimeException.class, () -> aspect.around(point, sysLogAnnotation("删除商品")));

		String exception = capturePublished().getException();
		assertTrue(exception.contains("rootCause"), "应带根因：" + exception);
		assertTrue(exception.contains("真正原因：id 非法"));
	}

	@Test
	@DisplayName("captureFailure=false：失败仍 publish，但不打 ERROR 标记（退回改造前语义）")
	void failureCaptureCanBeDisabled() throws Throwable {
		properties.setCaptureFailure(false);
		ProceedingJoinPoint point = pointFor("removeById", new Object[] { "123" });
		when(point.proceed()).thenThrow(new IllegalStateException("boom"));

		assertThrows(IllegalStateException.class, () -> aspect.around(point, sysLogAnnotation("删除商品")));

		com.zhiflow.upms.common.dto.SysLogPayload logged = capturePublished();
		assertEquals(CommonConstants.LOG_TYPE_0, logged.getType());
		assertNull(logged.getException());
	}

	@Test
	@DisplayName("publishEvent 抛异常时不得掩盖业务异常")
	void publishFailureMustNotMaskBusinessException() throws Throwable {
		doThrow(new RuntimeException("事件总线挂了")).when(publisher).publishEvent(any(SysLogEvent.class));
		ProceedingJoinPoint point = pointFor("removeById", new Object[] { "123" });
		IllegalStateException boom = new IllegalStateException("业务失败");
		when(point.proceed()).thenThrow(boom);

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> aspect.around(point, sysLogAnnotation("删除商品")));

		// 调用方必须看到业务异常，而不是「事件总线挂了」
		assertSame(boom, thrown);
	}

	@Test
	@DisplayName("publishEvent 抛异常时不影响成功返回值")
	void publishFailureMustNotBreakSuccessPath() throws Throwable {
		doThrow(new RuntimeException("事件总线挂了")).when(publisher).publishEvent(any(SysLogEvent.class));
		ProceedingJoinPoint point = pointFor("removeById", new Object[] { "123" });
		when(point.proceed()).thenReturn("ok");

		assertEquals("ok", aspect.around(point, sysLogAnnotation("删除商品")));
	}

	@Test
	@DisplayName("DELETE + @PathVariable：params 不再为空（改造前的核心缺陷）")
	void pathVariableIsCollected() throws Throwable {
		ProceedingJoinPoint point = pointFor("removeById", new Object[] { "123" });
		when(point.proceed()).thenReturn("ok");

		aspect.around(point, sysLogAnnotation("删除商品"));

		String params = capturePublished().getParams();
		assertTrue(params.contains("\"path\""), "应含 path 段：" + params);
		assertTrue(params.contains("123"), "应含路径变量值：" + params);
	}

	@Test
	@DisplayName("@RequestBody 被采集且敏感字段掩码")
	void requestBodyIsCollectedAndMasked() throws Throwable {
		OrderPayload payload = new OrderPayload("张三", "13800001111", "secret123");
		ProceedingJoinPoint point = pointFor("save", new Object[] { payload });
		when(point.proceed()).thenReturn("ok");

		aspect.around(point, sysLogAnnotation("新增订单"));

		String params = capturePublished().getParams();
		assertTrue(params.contains("张三"), "非敏感字段应保留：" + params);
		assertTrue(params.contains("***"), "敏感字段应掩码：" + params);
		assertTrue(!params.contains("13800001111"), "手机号不得原文落库：" + params);
		assertTrue(!params.contains("secret123"), "密码不得原文落库：" + params);
	}

	@Test
	@DisplayName("captureBody=false：退回仅采集 query 参数")
	void bodyCaptureCanBeDisabled() throws Throwable {
		properties.setCaptureBody(false);
		OrderPayload payload = new OrderPayload("张三", "13800001111", "secret123");
		ProceedingJoinPoint point = pointFor("save", new Object[] { payload });
		when(point.proceed()).thenReturn("ok");

		aspect.around(point, sysLogAnnotation("新增订单"));

		String params = capturePublished().getParams();
		assertTrue(!params.contains("张三"), "关闭后不应含 body：" + params);
	}

	@Test
	@DisplayName("gRPC 服务端经 InternalCallContext 调 @SysLog 方法：业务照常执行，审计照常留痕")
	void internalCallContextMustNotBlockBusiness() throws Throwable {
		// 2026-09-16 生产形状：SmsGrpcService → InternalCallContext.call → SmsController.sendSmsInner。
		// gRPC 线程上没有外层 HTTP 请求，造出来的内部请求 getRequestURI() 为 null，
		// 采集时 URLUtil.getPath(null) NPE，业务根本没执行 —— 全站短信验证码发不出去。
		RequestContextHolder.resetRequestAttributes();
		ProceedingJoinPoint point = pointFor("removeById", new Object[] { "123" });
		when(point.proceed()).thenReturn("ok");

		Object result = InternalCallContext.call(() -> aspect.around(point, sysLogAnnotation("内部发送短信")));

		assertEquals("ok", result);
		verify(point).proceed();
		com.zhiflow.upms.common.dto.SysLogPayload logged = capturePublished();
		assertEquals("内部发送短信", logged.getTitle());
		assertNull(logged.getRequestUri());
		// 走的是完整采集而不是切面的最小记录兜底：入参照常落审计，与 REST 路径一致
		assertTrue(logged.getParams().contains("123"), "应含入参：" + logged.getParams());
	}

	@Test
	@DisplayName("审计采集本身抛异常：业务照常执行，仍发布一条带标题的最小审计")
	void collectionFailureMustNotBlockBusiness() throws Throwable {
		// 没有任何请求上下文（定时任务 / MQ 消费线程调到 @SysLog 方法）：采集第一步就抛。
		RequestContextHolder.resetRequestAttributes();
		ProceedingJoinPoint point = pointFor("removeById", new Object[] { "123" });
		when(point.proceed()).thenReturn("ok");

		assertEquals("ok", aspect.around(point, sysLogAnnotation("删除商品")));

		verify(point).proceed();
		com.zhiflow.upms.common.dto.SysLogPayload logged = capturePublished();
		assertEquals("删除商品", logged.getTitle());
		assertEquals(CommonConstants.LOG_TYPE_0, logged.getType());
	}

	// ---------- helpers ----------

	private com.zhiflow.upms.common.dto.SysLogPayload capturePublished() {
		ArgumentCaptor<SysLogEvent> captor = ArgumentCaptor.forClass(SysLogEvent.class);
		verify(publisher).publishEvent(captor.capture());
		return (com.zhiflow.upms.common.dto.SysLogPayload) captor.getValue().getSource();
	}

	private ProceedingJoinPoint pointFor(String methodName, Object[] args) {
		ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
		MethodSignature signature = mock(MethodSignature.class);
		Method method = findMethod(methodName);

		when(point.getSignature()).thenReturn(signature);
		when(signature.getMethod()).thenReturn(method);
		when(signature.getName()).thenReturn(methodName);
		when(signature.getParameterNames()).thenReturn(new String[] { "id" });
		when(point.getArgs()).thenReturn(args);
		when(point.getTarget()).thenReturn(new DummyController());
		return point;
	}

	private Method findMethod(String name) {
		for (Method method : DummyController.class.getDeclaredMethods()) {
			if (method.getName().equals(name)) {
				return method;
			}
		}
		throw new IllegalStateException("测试桩方法不存在: " + name);
	}

	private SysLog sysLogAnnotation(String value) {
		return sysLogAnnotation(value, "");
	}

	/** content 为 S4 新增的可选属性；空字符串 = 不启用模板，等同改造前行为 */
	private SysLog sysLogAnnotation(String value, String content) {
		return new SysLog() {
			@Override
			public Class<? extends java.lang.annotation.Annotation> annotationType() {
				return SysLog.class;
			}

			@Override
			public String value() {
				return value;
			}

			@Override
			public String content() {
				return content;
			}

			@Override
			public boolean captureBody() {
				return true;
			}

			@Override
			public boolean captureRequestBody() {
				return true;
			}
		};
	}

	/** 提供真实反射签名的桩控制器 */
	static class DummyController {

		public String removeById(@PathVariable("id") String id) {
			return "ok";
		}

		public String save(@RequestBody OrderPayload payload) {
			return "ok";
		}
	}

	record OrderPayload(String name, String mobile, String password) {
	}
}
