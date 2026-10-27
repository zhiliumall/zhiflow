package com.zhiflow.common.core.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.SocketTimeoutException;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

	@AfterEach
	void cleanup() {
		RequestContextHolder.resetRequestAttributes();
		MDC.clear();
	}

	@ParameterizedTest(name = "code={0} status={1} → {2}")
	@CsvSource(nullValues = "null", value = {
			"null, 200, success",
			"null, 302, success",
			"null, 404, biz_reject",
			"null, 424, biz_reject",
			"null, 500, error",
			"null, 503, error",
			// 错误码优先
			"SYS-COMMON-500, 200, error",
			"EXT-SMS-502, 200, error",
			"BIZ-COMMON-001, 200, biz_reject",
			// 回调路径上的 BizException 按 R5 回 500，但它是业务拒绝，不算服务故障
			"BIZ-COMMON-001, 500, biz_reject",
			"SYS-DB-500, 400, error",
			// 认不出的前缀按状态码
			"WHATEVER, 200, success",
			"'', 500, error"
	})
	@DisplayName("outcome：错误码优先，其次状态码")
	void outcome(String code, int status, String expected) {
		assertThat(ErrorCode.outcome(code, status)).isEqualTo(expected);
	}

	@Test
	@DisplayName("未捕获异常的系统码：沿 cause 链识别数据访问与超时，其余 SYS-COMMON-500")
	void systemCodeOf() {
		assertThat(ErrorCode.systemCodeOf(new RuntimeException("x", new SQLException("boom"))))
			.isEqualTo(ErrorCode.SYS_DB);
		assertThat(ErrorCode.systemCodeOf(new IllegalStateException(new SocketTimeoutException())))
			.isEqualTo(ErrorCode.SYS_TIMEOUT);
		assertThat(ErrorCode.systemCodeOf(new NullPointerException())).isEqualTo(ErrorCode.SYS_COMMON);
		assertThat(ErrorCode.systemCodeOf(null)).isEqualTo(ErrorCode.SYS_COMMON);
		assertThat(ErrorCode.ext("sms")).isEqualTo("EXT-SMS-502");
	}

	@Test
	@DisplayName("codeOf：实现 ErrorCoded 且格式合规用它自己的码；不合规 / 没实现 / 取码抛错都退回默认码")
	void codeOf() {
		class Paused extends RuntimeException implements ErrorCoded {
			private final String code;

			Paused(String code) {
				this.code = code;
			}

			@Override
			public String errorCode() {
				if (code == null) {
					throw new IllegalStateException("boom");
				}
				return code;
			}
		}
		assertThat(ErrorCode.codeOf(new Paused("BIZ-AFFIL-090"), ErrorCode.BIZ_COMMON)).isEqualTo("BIZ-AFFIL-090");
		assertThat(ErrorCode.codeOf(new Paused("affil-090"), ErrorCode.BIZ_COMMON)).isEqualTo(ErrorCode.BIZ_COMMON);
		assertThat(ErrorCode.codeOf(new Paused("BIZ-AFFIL-90"), ErrorCode.BIZ_COMMON)).isEqualTo(ErrorCode.BIZ_COMMON);
		assertThat(ErrorCode.codeOf(new Paused(null), ErrorCode.BIZ_COMMON)).isEqualTo(ErrorCode.BIZ_COMMON);
		assertThat(ErrorCode.codeOf(new IllegalStateException(), ErrorCode.SYS_COMMON)).isEqualTo(ErrorCode.SYS_COMMON);
	}

	@Test
	@DisplayName("markCurrentRequest：有 servlet 请求上下文时写响应头 + MDC")
	void markWritesHeaderAndMdc() {
		MockHttpServletResponse response = new MockHttpServletResponse();
		RequestContextHolder.setRequestAttributes(
				new ServletRequestAttributes(new MockHttpServletRequest(), response));

		ErrorCode.markCurrentRequest(ErrorCode.BIZ_PARAM);

		assertThat(response.getHeader(ErrorCode.HEADER)).isEqualTo("BIZ-PARAM-400");
		assertThat(MDC.get(ErrorCode.MDC_KEY)).isEqualTo("BIZ-PARAM-400");
	}

	@Test
	@DisplayName("markCurrentRequest：没有请求上下文（异步线程 / 直接 new 处理器）只写 MDC、不抛")
	void markWithoutRequestContext() {
		ErrorCode.markCurrentRequest(ErrorCode.SYS_COMMON);
		ErrorCode.markCurrentRequest(null);
		assertThat(MDC.get(ErrorCode.MDC_KEY)).isEqualTo("SYS-COMMON-500");
	}

}
