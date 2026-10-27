package com.zhiflow.upms.admin.controller;

import com.zhiflow.common.core.util.R;
import com.zhiflow.upms.admin.service.SysUserService;
import com.zhiflow.upms.common.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M1：GET /user/{id} 必须带权限码，且不回出密码哈希。
 */
class SysUserGetByIdExposureTest {

	@Test
	@DisplayName("GET /user/{id} 带 sys:user:get 权限注解")
	void hasPermissionAnnotation() throws Exception {
		PreAuthorize pa = SysUserController.class.getMethod("user", String.class).getAnnotation(PreAuthorize.class);
		assertNotNull(pa, "GET /user/{id} 缺权限注解");
		assertEquals("@ato.hasAuthority('sys:user:get')", pa.value());
	}

	@Test
	@DisplayName("返回体不含 password，其余字段照常")
	void passwordStripped() throws Exception {
		SysUserService userService = mock(SysUserService.class);
		SysUser u = new SysUser();
		u.setId("u1");
		u.setUsername("alice");
		u.setPassword("$2a$10$hash");
		when(userService.getById("u1")).thenReturn(u);

		R r = controllerWith(userService).user("u1");

		SysUser out = (SysUser) r.getData();
		assertNull(out.getPassword());
		assertEquals("alice", out.getUsername());
	}

	@Test
	@DisplayName("用户不存在：返回 null 数据，不抛异常")
	void missingUser() throws Exception {
		SysUserService userService = mock(SysUserService.class);
		R r = controllerWith(userService).user("nope");
		assertNull(r.getData());
	}

	private static SysUserController controllerWith(SysUserService userService) throws Exception {
		Constructor<?> ctor = SysUserController.class.getDeclaredConstructors()[0];
		Object[] args = new Object[ctor.getParameterCount()];
		Class<?>[] types = ctor.getParameterTypes();
		for (int i = 0; i < types.length; i++) {
			args[i] = types[i] == SysUserService.class ? userService : mock(types[i]);
		}
		return (SysUserController) ctor.newInstance(args);
	}
}
