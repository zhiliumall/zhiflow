package com.zhiflow.auth.handler;

import com.zhiflow.api.upms.LogLoginApi;
import com.zhiflow.common.core.util.ClientIpResolver;
import com.zhiflow.common.core.util.SpringContextHolder;
import com.zhiflow.upms.common.dto.SysLogLoginPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 登录日志的来源 IP（CARD-06）。
 *
 * <p>曾经 {@code sys_log_login} 记下的全是网关容器地址：处理器用的是 {@code request.getRemoteAddr()}，
 * 而 auth 前面隔着 openresty + 网关。开关开后取 openresty 覆盖写的真实客户端。日志是异步写的，IP 必须在请求线程上取好。
 */
class LoginLogClientIpTest {

	private static final String DOCKER_GW = "172.18.0.1";

	private static final String REAL = "223.74.1.1";

	private final LogLoginApi logLoginApi = mock(LogLoginApi.class);

	private GenericApplicationContext context;

	@BeforeEach
	void errorConverterNeedsJsonMapper() {
		context = new GenericApplicationContext();
		context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
		context.refresh();
		new SpringContextHolder().setApplicationContext(context);
	}

	@AfterEach
	void tearDown() {
		ClientIpResolver.bindSwitch(null);
		SpringContextHolder.clearHolder();
		context.close();
	}

	private SysLogLoginPayload failLogin() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
		request.setRemoteAddr(DOCKER_GW);
		request.addHeader("X-Real-IP", REAL);
		request.addHeader("X-Forwarded-For", "8.8.8.8, " + REAL + ", " + DOCKER_GW);
		request.addHeader("X-Client-IP", "9.9.9.9");
		request.addParameter("username", "someone");
		request.addParameter("grant_type", "password");

		new BaseAuthenticationFailureEventHandler(logLoginApi).onAuthenticationFailure(request,
				new MockHttpServletResponse(), new OAuth2AuthenticationException(new OAuth2Error("invalid_grant")));

		ArgumentCaptor<SysLogLoginPayload> saved = ArgumentCaptor.forClass(SysLogLoginPayload.class);
		verify(logLoginApi, timeout(5000)).saveLogLogin(saved.capture(), any());
		return saved.getValue();
	}

	@Test
	@DisplayName("开关关（现状）：登录失败日志记的是网关容器地址")
	void switchOffRecordsGatewayAddress() {
		assertThat(failLogin().getRemoteAddr()).isEqualTo(DOCKER_GW);
	}

	@Test
	@DisplayName("开关开：登录失败日志记真实客户端；伪造的 XFF 左段与 X-Client-IP 不影响")
	void switchOnRecordsRealClient() {
		ClientIpResolver.bindSwitch(() -> true);
		assertThat(failLogin().getRemoteAddr()).isEqualTo(REAL);
	}

}
