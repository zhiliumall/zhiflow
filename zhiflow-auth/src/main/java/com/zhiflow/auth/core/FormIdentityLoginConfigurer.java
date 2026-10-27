package com.zhiflow.auth.core;

import com.zhiflow.auth.handler.SsoLogoutSuccessHandler;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;

/**
 * @author 辰
 * @data 2023年10月26日18:27:35
 *
 * 基于授权码模式 统一认证登录 spring security & sas 都可以使用 所以抽取成 HttpConfigurer
 */
public final class FormIdentityLoginConfigurer
		extends AbstractHttpConfigurer<FormIdentityLoginConfigurer, HttpSecurity> {

	@Override
	public void init(HttpSecurity http) {
		// 2026-09-25：表单登录（/token/login 页面 + /token/form 提交 + FreeMarker 模板）已下线——
		// 模板文件从未存在、四端前端无跳转、access log 里只有扫描器命中。未认证请求不再 302 到死页面。
		http.logout(logout -> logout.logoutSuccessHandler(new SsoLogoutSuccessHandler())
				.deleteCookies("JSESSIONID")
				.invalidateHttpSession(true)) // SSO登出成功处理

			.csrf(AbstractHttpConfigurer::disable);
	}

}
