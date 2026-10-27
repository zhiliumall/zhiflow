package com.zhiflow.upms.admin.controller;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.upms.admin.service.SysTenantService;
import com.zhiflow.upms.common.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 账号所属租户已被删除时，登录链路的 {@code setUserLockFlag} 不许 NPE。
 *
 * <p>2026-09-15 12:53 / 21:11：gRPC {@code getUserInfoByPhone} 两次在这里抛 NPE（sysTenant 为 null），手机号登录回 500。
 */
class SysUserControllerLockFlagTest {

	@Test
	@DisplayName("租户不存在：按锁定处理，不抛异常")
	void missingTenantLocksUser() throws Exception {
		SysTenantService tenantService = mock(SysTenantService.class);
		when(tenantService.getById("404")).thenReturn(null);
		SysUserController controller = controllerWith(tenantService);

		SysUser user = new SysUser();
		user.setId("u1");
		user.setTenantId("404");
		user.setLockFlag(CommonConstants.STATUS_NORMAL);

		controller.setUserLockFlag(user);

		assertEquals(CommonConstants.STATUS_LOCK, user.getLockFlag());
	}

	/** 构造器参数全部 mock，只替换租户服务 —— 本测试只关心 setUserLockFlag。 */
	private static SysUserController controllerWith(SysTenantService tenantService) throws Exception {
		Constructor<?> ctor = SysUserController.class.getDeclaredConstructors()[0];
		Object[] args = new Object[ctor.getParameterCount()];
		Class<?>[] types = ctor.getParameterTypes();
		for (int i = 0; i < types.length; i++) {
			args[i] = types[i] == SysTenantService.class ? tenantService : mock(types[i]);
		}
		return (SysUserController) ctor.newInstance(args);
	}

}
