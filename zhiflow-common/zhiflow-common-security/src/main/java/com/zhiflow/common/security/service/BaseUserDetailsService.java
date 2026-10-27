package com.zhiflow.common.security.service;

import com.zhiflow.common.security.entity.BaseUser;
import org.springframework.core.Ordered;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * @author 辰
 * @date 2023年10月26日15:20:18
 */
public interface BaseUserDetailsService extends UserDetailsService, Ordered {

	/**
	 * 是否支持此客户端校验
	 * @param clientId 目标客户端
	 * @return true/false
	 */
	default boolean support(String clientId, String grantType) {
		return true;
	}

	/**
	 * 排序值 默认取最大的
	 * @return 排序值
	 */
	@Override
	default int getOrder() {
		return 0;
	}

	/**
	 * 通过用户实体查询
	 * @param user user
	 * @return
	 */
	default UserDetails loadUserByUser(BaseUser user) {
		return this.loadUserByUsername(user.getUsername());
	}
}
