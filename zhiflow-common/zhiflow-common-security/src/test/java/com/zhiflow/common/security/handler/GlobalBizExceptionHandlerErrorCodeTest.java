package com.zhiflow.common.security.handler;

import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.core.observability.ErrorCode;
import com.zhiflow.common.core.observability.ErrorCoded;
import com.zhiflow.common.core.observability.TraceIds;
import com.zhiflow.common.security.component.GlobalExceptionHandlerResolver;
import com.zhiflow.common.security.component.ResourceAuthExceptionEntryPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 错误码契约：每个分支写对 {@code X-Error-Code}，<b>状态码一个都不变</b>
 * （尤其是回调路径上的 BizException 仍回 500）；错误号 = 当前 trace id 末 8 位，sys_error_log 存完整 trace id。
 */
class GlobalBizExceptionHandlerErrorCodeTest {

	private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

	private final GlobalBizExceptionHandler handler = new GlobalBizExceptionHandler();

	@AfterEach
	void cleanup() {
		MDC.clear();
	}

	private MockMvc mvc(Object advice) {
		return MockMvcBuilders.standaloneSetup(new ThrowingController()).setControllerAdvice(advice).build();
	}

	@Test
	@DisplayName("业务拒绝：200 + BIZ-COMMON-001")
	void bizException() throws Exception {
		mvc(handler).perform(get("/t/biz"))
			.andExpect(status().isOk())
			.andExpect(header().string(ErrorCode.HEADER, "BIZ-COMMON-001"));
	}

	@Test
	@DisplayName("回调路径的业务拒绝：仍是 500（R5 回调闸），错误码仍是 BIZ-COMMON-001")
	void bizExceptionOnCallbackKeeps500() throws Exception {
		mvc(handler).perform(post("/t/notify-refunds/1"))
			.andExpect(status().isInternalServerError())
			.andExpect(header().string(ErrorCode.HEADER, "BIZ-COMMON-001"));
	}

	@Test
	@DisplayName("自带错误码的 BizException 子类（实现 ErrorCoded，如结算暂停）用它自己的码；状态码照旧，回调路径仍 500")
	void errorCodedBizException() throws Exception {
		mvc(handler).perform(get("/t/paused"))
			.andExpect(status().isOk())
			.andExpect(header().string(ErrorCode.HEADER, "BIZ-AFFIL-090"));
		mvc(handler).perform(post("/t/paused/notify/1"))
			.andExpect(status().isInternalServerError())
			.andExpect(header().string(ErrorCode.HEADER, "BIZ-AFFIL-090"));
	}

	@Test
	@DisplayName("未捕获异常：500 + SYS-COMMON-500；数据访问异常：SYS-DB-500")
	void systemErrors() throws Exception {
		mvc(handler).perform(get("/t/npe"))
			.andExpect(status().isInternalServerError())
			.andExpect(header().string(ErrorCode.HEADER, "SYS-COMMON-500"));
		mvc(handler).perform(get("/t/db"))
			.andExpect(status().isInternalServerError())
			.andExpect(header().string(ErrorCode.HEADER, "SYS-DB-500"));
	}

	@Test
	@DisplayName("参数 / 方法 / 找不到：状态码照旧，错误码 BIZ-PARAM-400 / BIZ-METHOD-405")
	void clientErrors() throws Exception {
		mvc(handler).perform(get("/t/typed/abc"))
			.andExpect(status().isBadRequest())
			.andExpect(header().string(ErrorCode.HEADER, "BIZ-PARAM-400"));
		mvc(handler).perform(get("/t/only-delete"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(header().string(ErrorCode.HEADER, "BIZ-METHOD-405"));
		mvc(handler).perform(get("/t/illegal"))
			.andExpect(status().isOk())
			.andExpect(header().string(ErrorCode.HEADER, "BIZ-PARAM-400"));
	}

	@Test
	@DisplayName("对照：正常返回的请求不带 X-Error-Code")
	void successHasNoErrorCode() throws Exception {
		mvc(handler).perform(get("/t/ok"))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist(ErrorCode.HEADER));
	}

	@Test
	@DisplayName("低优先级的 GlobalExceptionHandlerResolver 同样写错误码、同样用 trace id 末 8 位")
	void resolverAlsoMarks() throws Exception {
		MDC.put(TraceIds.MDC_TRACE_ID, TRACE);
		mvc(new GlobalExceptionHandlerResolver()).perform(get("/t/npe"))
			.andExpect(status().isInternalServerError())
			.andExpect(header().string(ErrorCode.HEADER, "SYS-COMMON-500"))
			.andExpect(jsonPath("$.msg").value("服务异常，请稍后重试（错误号 0e0e4736）"));
	}

	@Test
	@DisplayName("令牌鉴权入口：401 → BIZ-AUTH-401，令牌失效 424 → BIZ-AUTH-424")
	void authEntryPoint() {
		StaticMessageSource messages = new StaticMessageSource();
		messages.addMessage("OAuth2ResourceOwnerBaseAuthenticationProvider.tokenExpired", Locale.getDefault(), "令牌失效");
		ResourceAuthExceptionEntryPoint entryPoint = new ResourceAuthExceptionEntryPoint(new JsonMapper(), messages);

		MockHttpServletResponse unauthorized = new MockHttpServletResponse();
		entryPoint.commence(new MockHttpServletRequest(), unauthorized, new BadCredentialsException("bad"));
		assertThat(unauthorized.getStatus()).isEqualTo(401);
		assertThat(unauthorized.getHeader(ErrorCode.HEADER)).isEqualTo("BIZ-AUTH-401");

		MockHttpServletResponse expired = new MockHttpServletResponse();
		entryPoint.commence(new MockHttpServletRequest(), expired, new InsufficientAuthenticationException("x"));
		assertThat(expired.getStatus()).isEqualTo(424);
		assertThat(expired.getHeader(ErrorCode.HEADER)).isEqualTo("BIZ-AUTH-424");
	}

	/** 模拟 mall-common 的结算暂停异常：继承 BizException、实现 common-core 的 ErrorCoded。 */
	static class PausedException extends BizException implements ErrorCoded {

		PausedException() {
			super("结算暂停中");
		}

		@Override
		public String errorCode() {
			return ErrorCode.BIZ_AFFILIATE_PAUSED;
		}

	}

	@RestController
	static class ThrowingController {

		@GetMapping("/t/ok")
		String ok() {
			return "ok";
		}

		@GetMapping("/t/biz")
		String biz() {
			throw new BizException("余额不足");
		}

		@PostMapping("/t/notify-refunds/{tenantId}")
		String notifyRefunds(@PathVariable String tenantId) {
			throw new BizException("请重新提交");
		}

		@GetMapping("/t/npe")
		String npe() {
			throw new NullPointerException("x");
		}

		@GetMapping("/t/npe-unique")
		String npeUnique() {
			throw new NullPointerException("unique");
		}

		@GetMapping("/t/npe-other")
		String npeOther() {
			throw new NullPointerException("other");
		}

		@GetMapping("/t/db")
		String db() {
			throw new DataIntegrityViolationException("duplicate key");
		}

		@GetMapping("/t/typed/{id}")
		String typed(@PathVariable Long id) {
			return "ok";
		}

		@DeleteMapping("/t/only-delete")
		String onlyDelete() {
			return "ok";
		}

		@GetMapping("/t/illegal")
		String illegal() {
			throw new IllegalArgumentException("参数不对");
		}

		@GetMapping("/t/paused")
		String paused() {
			throw new PausedException();
		}

		@PostMapping("/t/paused/notify/{tenantId}")
		String pausedOnCallback(@PathVariable String tenantId) {
			throw new PausedException();
		}

	}

}
