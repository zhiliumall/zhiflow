package com.zhiflow.weixin.admin.security;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.weixin.admin.mapper.WxAppMapper;
import com.zhiflow.weixin.common.entity.WxApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 越权普查 H6：后台按 appId 取微信服务前的租户归属校验。
 */
class WxAppTenantScopeGuardTest {

	private static final String TENANT_A = "1001";

	private static final String TENANT_B = "2002";

	private static final String OWN_APP = "wx-own";

	private static final String FOREIGN_APP = "wx-foreign";

	private WxAppMapper mapper;

	private MockEnvironment env;

	private WxAppTenantScopeGuard guard;

	private MockHttpServletRequest request;

	@BeforeEach
	void setUp() {
		mapper = mock(WxAppMapper.class);
		when(mapper.selectById(OWN_APP)).thenReturn(app(OWN_APP, TENANT_A));
		when(mapper.selectById(FOREIGN_APP)).thenReturn(app(FOREIGN_APP, TENANT_B));
		env = new MockEnvironment();
		env.setProperty(WxAppTenantScopeGuard.ENABLED, "true");
		guard = new WxAppTenantScopeGuard(mapper, env);
		request = new MockHttpServletRequest();
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
		login(CommonConstants.USER_TYPE_1, TENANT_A);
		TenantContextHolder.setTenantId(TENANT_A);
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
	}

	@Test
	@DisplayName("租户后台用别家 appId：拒绝（403）")
	void foreignAppIdDenied() {
		assertThrows(AccessDeniedException.class, () -> guard.check(FOREIGN_APP));
	}

	@Test
	@DisplayName("租户后台用本租户 appId：放行；同一请求再取不重复查库")
	void ownAppIdAllowedAndMemoized() {
		assertDoesNotThrow(() -> guard.check(OWN_APP));
		assertDoesNotThrow(() -> guard.check(OWN_APP));
		verify(mapper, times(1)).selectById(OWN_APP);
	}

	@Test
	@DisplayName("appId 在 wx_app 里不存在：拒绝")
	void unknownAppIdDenied() {
		assertThrows(AccessDeniedException.class, () -> guard.check("wx-missing"));
	}

	@Test
	@DisplayName("取不到当前租户：拒绝，不查库")
	void blankTenantDenied() {
		TenantContextHolder.clear();
		assertThrows(AccessDeniedException.class, () -> guard.check(OWN_APP));
		verify(mapper, never()).selectById(any());
	}

	@Test
	@DisplayName("appId 为空：拒绝")
	void blankAppIdDenied() {
		assertThrows(AccessDeniedException.class, () -> guard.check(" "));
	}

	@Test
	@DisplayName("商城管理员只按租户判：本租户 appId 放行，别的租户拒绝")
	void mallAdminCheckedByTenantOnly() {
		login(CommonConstants.USER_TYPE_1, TENANT_A);
		assertDoesNotThrow(() -> guard.check(OWN_APP));
		assertThrows(AccessDeniedException.class, () -> guard.check(FOREIGN_APP));
	}

	@Test
	@DisplayName("平台超管：保留跨租户能力，不查库")
	void platformAdminBypasses() {
		login(CommonConstants.USER_TYPE_S, CommonConstants.SYSTEM_TENANT_ID);
		TenantContextHolder.setTenantId(TENANT_B);
		assertDoesNotThrow(() -> guard.check(OWN_APP));
		verify(mapper, never()).selectById(any());
	}

	@Test
	@DisplayName("服务间 / 进程内调用（from: Y）：放行")
	void internalCallBypasses() {
		request.addHeader(SecurityConstants.FROM, SecurityConstants.FROM_IN);
		assertDoesNotThrow(() -> guard.check(FOREIGN_APP));
		verify(mapper, never()).selectById(any());
	}

	@Test
	@DisplayName("匿名请求（消息接收 / 开放平台回调 / 授权跳转）：放行")
	void anonymousBypasses() {
		SecurityContextHolder.clearContext();
		assertDoesNotThrow(() -> guard.check(FOREIGN_APP));
		verify(mapper, never()).selectById(any());
	}

	@Test
	@DisplayName("线程上没有 HTTP 请求（gRPC / MQ / 定时）：放行")
	void noHttpRequestBypasses() {
		RequestContextHolder.resetRequestAttributes();
		assertDoesNotThrow(() -> guard.check(FOREIGN_APP));
		verify(mapper, never()).selectById(any());
	}

	@Test
	@DisplayName("开关缺失：按关，别家 appId 照旧放行")
	void switchMissingMeansOff() {
		guard = new WxAppTenantScopeGuard(mapper, new MockEnvironment());
		assertDoesNotThrow(() -> guard.check(FOREIGN_APP));
		verify(mapper, never()).selectById(any());
	}

	@Test
	@DisplayName("开关写错（ture）：按关，不抛")
	void switchMalformedMeansOff() {
		env.setProperty(WxAppTenantScopeGuard.ENABLED, "ture");
		assertDoesNotThrow(() -> guard.check(FOREIGN_APP));
		verify(mapper, never()).selectById(any());
	}

	@Test
	@DisplayName("开关显式 false：关")
	void switchFalseMeansOff() {
		env.setProperty(WxAppTenantScopeGuard.ENABLED, "false");
		assertDoesNotThrow(() -> guard.check(FOREIGN_APP));
	}

	private static void login(String type, String tenantId) {
		BaseUser user = new BaseUser("u-1", null, type, tenantId, "tester", "N/A", null,
				true, true, true, true, List.of());
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
	}

	private static WxApp app(String id, String tenantId) {
		WxApp app = new WxApp();
		app.setId(id);
		app.setTenantId(tenantId);
		return app;
	}

}
