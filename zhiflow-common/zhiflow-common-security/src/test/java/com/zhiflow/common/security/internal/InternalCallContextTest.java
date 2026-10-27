package com.zhiflow.common.security.internal;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.common.security.component.BaseSecurityInsideAspect;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内内部调用身份。
 *
 * <p>这几条守的是同一件事：<b>身份必须只在那一次调用里存在</b>。
 * 没还原 = 后续代码顶着内部身份跑 = {@code @Inside} 形同虚设 ——
 * 而这种泄漏在生产上表现为「本来该 403 的端点忽然能过」，没有任何报错。
 */
class InternalCallContextTest {

	@AfterEach
	void clear() {
		RequestContextHolder.resetRequestAttributes();
	}

	// ------------------------------------------------------------ 身份本身

	@Test
	@DisplayName("调用期间安全切面看得到 from=Y —— 这是 @Inside 端点在单体里能过的唯一原因")
	void insideAspectPassesDuringInternalCall() {
		bindOuterRequest();   // 外层 C 端请求：网关已把 from 剥掉
		BaseSecurityInsideAspect aspect = aspectOnCurrentRequest();

		assertThrows(AccessDeniedException.class, () -> aspect.around(joinPoint(), inside(true)),
				"外层 C 端请求本来就该被拒");

		InternalCallContext.run(() -> assertDoesNotThrow(() -> aspect.around(joinPoint(), inside(true))));

		assertThrows(AccessDeniedException.class, () -> aspect.around(joinPoint(), inside(true)),
				"调用结束后身份必须立刻消失");
	}

	@Test
	@DisplayName("被调方只看得到 from 与租户头 —— 不给它今天 HTTP 路径下看不到的东西")
	void calleeSeesOnlyInternalHeaders() {
		MockHttpServletRequest outer = bindOuterRequest();
		outer.addHeader("tenant-id", "1");
		outer.addHeader("third-session", "SHOULD-NOT-LEAK");
		outer.addHeader("client-type", "H5");

		InternalCallContext.run(() -> {
			var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
			assertEquals(SecurityConstants.FROM_IN, request.getHeader(SecurityConstants.FROM));
			assertEquals("1", request.getHeader("tenant-id"), "租户头要透传，与 tenantRequestInterceptor 一致");
			assertNull(request.getHeader("third-session"),
					"外层 C 端会话不能漏给被调方 —— HTTP 路径下它也看不到，漏了就是比现状更宽");
			assertNull(request.getHeader("client-type"));
		});
	}

	// ------------------------------------------------------------ 还原

	@Test
	@DisplayName("正常返回后还原成进入前的那一个")
	void restoresPreviousAttributesOnReturn() {
		RequestAttributes outer = new ServletRequestAttributes(bindOuterRequest());
		RequestContextHolder.setRequestAttributes(outer);

		String result = InternalCallContext.call(() -> "ok");

		assertEquals("ok", result);
		assertSame(outer, RequestContextHolder.getRequestAttributes());
	}

	@Test
	@DisplayName("⚠️ 抛异常也必须还原 —— finally 漏了的话，一次失败的内部调用会把身份留给后续代码")
	void restoresPreviousAttributesOnException() {
		RequestAttributes outer = new ServletRequestAttributes(bindOuterRequest());
		RequestContextHolder.setRequestAttributes(outer);

		assertThrows(IllegalStateException.class,
				() -> InternalCallContext.call(() -> { throw new IllegalStateException("boom"); }));

		assertSame(outer, RequestContextHolder.getRequestAttributes());
	}

	@Test
	@DisplayName("没有外层请求时（定时任务线程）调用能跑，且跑完回到 null")
	void worksWithoutOuterRequest() {
		RequestContextHolder.resetRequestAttributes();

		assertEquals("ok", InternalCallContext.call(() -> {
			var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
			assertEquals(SecurityConstants.FROM_IN, request.getHeader(SecurityConstants.FROM));
			return "ok";
		}));

		assertNull(RequestContextHolder.getRequestAttributes(), "跑完不能留下一个凭空造出来的内部请求");
	}

	@Test
	@DisplayName("嵌套调用逐层还原（本地实现调本地实现是常态）")
	void nestedCallsRestoreLayerByLayer() {
		RequestAttributes outer = new ServletRequestAttributes(bindOuterRequest());
		RequestContextHolder.setRequestAttributes(outer);

		InternalCallContext.run(() -> {
			RequestAttributes first = RequestContextHolder.getRequestAttributes();
			InternalCallContext.run(() -> assertTrue(RequestContextHolder.getRequestAttributes() != first));
			assertSame(first, RequestContextHolder.getRequestAttributes(), "内层结束后要回到外层那个内部请求");
		});

		assertSame(outer, RequestContextHolder.getRequestAttributes());
	}

	@Test
	@DisplayName("operationId 正常返回后清理，历史无 operationId 调用不受影响")
	void operationIdRestoresAfterReturn() {
		assertNull(InternalCallContext.currentOperationId());
		String result = InternalCallContext.call("order-cancel:o-1", () -> {
			assertEquals("order-cancel:o-1", InternalCallContext.currentOperationId());
			return "ok";
		});

		assertEquals("ok", result);
		assertNull(InternalCallContext.currentOperationId());
	}

	@Test
	@DisplayName("operationId 嵌套逐层恢复，异常路径也不串值")
	void operationIdRestoresOnNestedException() {
		assertThrows(IllegalStateException.class, () -> InternalCallContext.call("outer", () -> {
			assertEquals("outer", InternalCallContext.currentOperationId());
			assertThrows(IllegalStateException.class,
					() -> InternalCallContext.call("inner", () -> {
						assertEquals("inner", InternalCallContext.currentOperationId());
						throw new IllegalStateException("boom");
					}));
			assertEquals("outer", InternalCallContext.currentOperationId());
			throw new IllegalStateException("outer-boom");
		}));
		assertNull(InternalCallContext.currentOperationId());
	}

	@Test
	@DisplayName("@Inside(value=false) 不受影响（全仓 7 处，是刻意对外开放的）")
	void insideFalseIsUntouched() {
		bindOuterRequest();
		assertDoesNotThrow(() -> aspectOnCurrentRequest().around(joinPoint(), inside(false)));
	}

	// ------------------------------------------------------------ 夹具

	/** 外层 C 端请求：网关已经把 from 头删掉了，所以这里刻意不加。 */
	private static MockHttpServletRequest bindOuterRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mallapi/order/page");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
		return request;
	}

	/**
	 * 切面在生产里注入的是一个按 {@code RequestContextHolder} 现解析的请求代理。
	 * 这里照着造一个，否则测的就不是「换上下文能不能骗过切面」而是「构造器传了什么」。
	 */
	private static BaseSecurityInsideAspect aspectOnCurrentRequest() {
		var proxy = (jakarta.servlet.http.HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
				InternalCallContextTest.class.getClassLoader(),
				new Class<?>[] { jakarta.servlet.http.HttpServletRequest.class },
				(p, method, args) -> method.invoke(
						((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest(),
						args));
		return new BaseSecurityInsideAspect(proxy);
	}

	private static JoinPoint joinPoint() {
		return (JoinPoint) java.lang.reflect.Proxy.newProxyInstance(
				InternalCallContextTest.class.getClassLoader(), new Class<?>[] { JoinPoint.class },
				(p, method, args) -> switch (method.getName()) {
					case "getSignature" -> (Signature) java.lang.reflect.Proxy.newProxyInstance(
							InternalCallContextTest.class.getClassLoader(), new Class<?>[] { Signature.class },
							(p2, m2, a2) -> "getName".equals(m2.getName()) ? "probe" : null);
					case "getTarget" -> new Object();
					case "toString" -> "probeJoinPoint";
					case "hashCode" -> 1;
					case "equals" -> false;
					default -> null;
				});
	}

	private static Inside inside(boolean value) {
		return new Inside() {
			@Override
			public Class<? extends Annotation> annotationType() {
				return Inside.class;
			}

			@Override
			public boolean value() {
				return value;
			}

			@Override
			public String[] field() {
				return new String[0];
			}
		};
	}
}
