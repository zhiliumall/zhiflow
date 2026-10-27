package com.zhiflow.common.security.service.impl;

import com.zhiflow.upms.common.dto.AuthUserIdentity;
import com.zhiflow.upms.common.dto.AuthUserInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「下次登录前必须修改密码」映射成凭证过期：密码校验通过后由 Security 拒绝发令牌；不影响账号启用与锁定判定。
 */
class BaseUserDetailsServiceImplCredentialsTest {

	private final BaseUserDetailsServiceImpl service = new BaseUserDetailsServiceImpl(null, null);

	@Test
	@DisplayName("改密标记 '1'：凭证过期；账号仍是启用、未锁定")
	void flaggedUserHasExpiredCredentials() {
		UserDetails details = service.getUserDetails(info("1"));

		assertFalse(details.isCredentialsNonExpired());
		assertTrue(details.isEnabled());
		assertTrue(details.isAccountNonLocked());
	}

	@Test
	@DisplayName("对照：标记 '0' 或缺省（老数据）都是正常凭证")
	void unflaggedUsersAreNormal() {
		assertTrue(service.getUserDetails(info("0")).isCredentialsNonExpired());
		assertTrue(service.getUserDetails(info(null)).isCredentialsNonExpired());
	}

	private static AuthUserInfo info(String flag) {
		AuthUserIdentity user = new AuthUserIdentity();
		user.setId("1");
		user.setUsername("admin");
		user.setPassword("$2a$10$abcdefghijklmnopqrstuv");
		user.setTenantId("-1");
		user.setType("-1");
		user.setLockFlag("0");
		user.setPasswordChangeRequired(flag);
		AuthUserInfo info = new AuthUserInfo();
		info.setUser(user);
		info.setRoles(new String[0]);
		info.setPermissions(new String[0]);
		return info;
	}
}
