package com.zhiflow.boot.edge;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.security.internal.InternalCallContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * from 头剥离：@Inside 边界不可伪造性的机器判据。
 *
 * <h2>反向验证</h2>
 * <ul>
 * <li>把 {@code FromStrippedRequest.getHeader} 改成直接 {@code super.getHeader}（不剥）→
 *     {@code stripsForgedFromHeader} 变红；</li>
 * <li>把 {@code isFrom} 的 {@code equalsIgnoreCase} 改成 {@code equals} →
 *     {@code strippingIsCaseInsensitive} 变红；</li>
 * <li>把过滤器改成同时清掉 {@code RequestContextHolder} 或包装内部请求 →
 *     {@code internalCallContextStillCarriesInternalIdentity} 变红。</li>
 * </ul>
 */
class FromHeaderStrippingFilterTest {

	private final FromHeaderStrippingFilter filter = new FromHeaderStrippingFilter();

	@AfterEach
	void resetRequestContext() {
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	@DisplayName("⭐ 外部请求伪造的 from: Y 被完全剥掉（getHeader/getHeaders/getHeaderNames 三个口）")
	void stripsForgedFromHeader() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/upms/tenant/inside/list");
		request.addHeader(SecurityConstants.FROM, SecurityConstants.FROM_IN);
		request.addHeader("tenant-id", "1");

		AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
		this.filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));

		HttpServletRequest downstream = seen.get();
		assertNull(downstream.getHeader(SecurityConstants.FROM), "getHeader 口没剥掉 from —— @Inside 可被伪造");
		assertFalse(downstream.getHeaders(SecurityConstants.FROM).hasMoreElements(), "getHeaders 口没剥掉 from");
		assertFalse(Collections.list(downstream.getHeaderNames()).stream()
			.anyMatch(SecurityConstants.FROM::equalsIgnoreCase), "getHeaderNames 口仍暴露 from");
		assertEquals("1", downstream.getHeader("tenant-id"), "其它头必须原样透传");
	}

	@Test
	@DisplayName("剥离对大小写不敏感（HTTP 头本身大小写不敏感，换个写法不能绕过）")
	void strippingIsCaseInsensitive() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
		request.addHeader("FROM", SecurityConstants.FROM_IN);

		AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
		this.filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));

		assertNull(seen.get().getHeader("from"));
		assertNull(seen.get().getHeader("From"));
		assertFalse(Collections.list(seen.get().getHeaderNames()).stream().anyMatch("FROM"::equalsIgnoreCase));
	}

	@Test
	@DisplayName("不带 from 的请求零开销直通（同一个请求实例，不包装）")
	void requestWithoutFromIsNotWrapped() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mallapi/goodsspu/page");

		AtomicReference<Object> seen = new AtomicReference<>();
		this.filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(req));

		assertSame(request, seen.get(), "没有 from 头时不应包装请求");
	}

	@Test
	@DisplayName("⭐ 与 InternalCallContext 不冲突：外层被剥、内部调用仍拿到 from=Y、退出后逐层还原")
	void internalCallContextStillCarriesInternalIdentity() throws Exception {
		// 模拟完整链路：外部请求带伪造 from → 本 filter 剥掉 → RequestContextFilter/DispatcherServlet
		// 用「包装后的请求」填 RequestContextHolder → 业务代码里 InternalCallContext.call 换上合成请求。
		// BaseSecurityInsideAspect 的判据 = RequestContextHolder 现解析出的请求头，本用例逐层断言。
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mallapi/orderinfo/x");
		request.addHeader(SecurityConstants.FROM, SecurityConstants.FROM_IN); // 外部伪造
		request.addHeader("tenant-id", "1");

		java.util.concurrent.atomic.AtomicBoolean reachedDownstream = new java.util.concurrent.atomic.AtomicBoolean();
		this.filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
			reachedDownstream.set(true);
			HttpServletRequest wrapped = (HttpServletRequest) req;
			// RequestContextFilter 干的事：用传进它的请求（= 包装后的）填 RequestContextHolder
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(wrapped));

			// ① 外层身份：from 已被剥掉
			assertNull(currentFrom(), "切面视角的外层请求仍能读到 from —— 剥离失效");

			// ② 内部调用：InternalCallContext 换上的合成请求带 from=Y，且透传租户头
			InternalCallContext.run(() -> {
				assertEquals(SecurityConstants.FROM_IN, currentFrom(),
						"内部调用没拿到 from=Y —— 剥离波及了 InternalCallContext，142 个 @Inside 端点会 403");
				assertEquals("1", currentRequest().getHeader("tenant-id"), "内部调用必须透传租户头");
			});

			// ③ 退出后还原为外层身份：from 仍然是剥掉的
			assertNull(currentFrom(), "内部身份泄漏到了外层 —— @Inside 形同虚设");
		});
		assertTrue(reachedDownstream.get(), "请求没到下游 —— 上面的断言一条都没执行，本用例是假绿");
	}

	private static HttpServletRequest currentRequest() {
		return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
	}

	private static String currentFrom() {
		return currentRequest().getHeader(SecurityConstants.FROM);
	}

}
