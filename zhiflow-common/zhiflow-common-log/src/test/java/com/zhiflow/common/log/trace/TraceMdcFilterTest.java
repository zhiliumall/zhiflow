package com.zhiflow.common.log.trace;

import com.zhiflow.common.core.observability.ErrorCode;
import com.zhiflow.common.core.observability.TraceIds;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求入口的链路标识：取值顺序、响应头、出口清理，以及错误日志拿到的就是这个 trace id。
 */
class TraceMdcFilterTest {

	private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

	private final TraceMdcFilter filter = new TraceMdcFilter();

	@AfterEach
	void cleanup() {
		MDC.clear();
	}

	@Test
	@DisplayName("入站 traceparent 合法 → 沿用它的 trace id：请求内 MDC 与响应头都是它，出口清空")
	void continuesIncomingTrace() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/goodsspu/page");
		request.addHeader(TraceIds.TRACEPARENT, "00-" + TRACE + "-00f067aa0ba902b7-01");
		MockHttpServletResponse response = new MockHttpServletResponse();
		Map<String, String> seen = new HashMap<>();

		filter.doFilter(request, response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
		}, (req, res, chain) -> {
			seen.put("trace", MDC.get(TraceIds.MDC_TRACE_ID));
			seen.put("span", MDC.get(TraceIds.MDC_SPAN_ID));
			MDC.put(TraceIds.MDC_TENANT_ID, "1");
			MDC.put(TraceIds.MDC_USER_ID, "42");
			MDC.put(ErrorCode.MDC_KEY, ErrorCode.BIZ_COMMON);
		}));

		assertThat(seen.get("trace")).isEqualTo(TRACE);
		assertThat(TraceIds.isValidSpanId(seen.get("span"))).isTrue();
		assertThat(response.getHeader(TraceIds.TRACE_ID_HEADER)).isEqualTo(TRACE);
		assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
	}

	@Test
	@DisplayName("没有 / 不合法的 traceparent → 现生成；同一请求的错误派发沿用首次那个")
	void generatesAndReusesOnRedispatch() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
		request.addHeader(TraceIds.TRACEPARENT, "garbage");
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		String generated = response.getHeader(TraceIds.TRACE_ID_HEADER);
		assertThat(TraceIds.isValidTraceId(generated)).isTrue();

		request.setDispatcherType(DispatcherType.ERROR);
		MockHttpServletResponse errorResponse = new MockHttpServletResponse();
		AtomicReference<String> inError = new AtomicReference<>();
		filter.doFilter(request, errorResponse, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
		}, (req, res, chain) -> inError.set(MDC.get(TraceIds.MDC_TRACE_ID))));
		assertThat(inError.get()).isEqualTo(generated);
	}

	@Test
	@DisplayName("链路里抛异常也要清 MDC（Tomcat 线程复用）")
	void clearsOnException() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
		try {
			filter.doFilter(request, new MockHttpServletResponse(),
					new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
					}, (req, res, chain) -> {
						throw new IllegalStateException("boom");
					}));
		}
		catch (Exception expected) {
			// 预期
		}
		assertThat(MDC.get(TraceIds.MDC_TRACE_ID)).isNull();
	}

	@Test
	@DisplayName("用户 id：已认证取主体、匿名 / 未认证不写")
	void userIdOf() {
		assertThat(UserMdcInterceptor.userIdOf(null)).isNull();
		assertThat(UserMdcInterceptor.userIdOf(new AnonymousAuthenticationToken("k", "anonymousUser",
				AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))).isNull();
		TestingAuthenticationToken authenticated = new TestingAuthenticationToken("admin", "pw", "ROLE_X");
		assertThat(UserMdcInterceptor.userIdOf(authenticated)).isEqualTo("admin");
	}

	@Test
	@DisplayName("装配：servlet 应用里注册过滤器（顺序 HIGHEST_PRECEDENCE+5）；登记在 AutoConfiguration.imports 里")
	void autoConfiguration() {
		assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
			.contains(TraceAutoConfiguration.class.getName());
		new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(TraceAutoConfiguration.class))
			.run(context -> {
				assertThat(context).hasNotFailed();
				FilterRegistrationBean<?> registration = context.getBean("traceMdcFilterRegistration",
						FilterRegistrationBean.class);
				assertThat(registration.getOrder()).isEqualTo(TraceAutoConfiguration.TRACE_FILTER_ORDER);
			});
		new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(TraceAutoConfiguration.class))
			.withPropertyValues("observability.trace-mdc.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean("traceMdcFilterRegistration"));
	}

}
