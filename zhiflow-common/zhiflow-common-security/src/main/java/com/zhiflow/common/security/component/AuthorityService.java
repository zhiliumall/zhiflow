package com.zhiflow.common.security.component;

import cn.hutool.core.util.ArrayUtil;
import com.zhiflow.common.security.util.SecurityUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.StringUtils;

import java.util.Collection;

/**
 * @author 辰
 * @date 2023年10月26日14:35:34
 * 接口权限判断
 */
@Component("ato")
public class AuthorityService {
	/**
	 * 判断接口是否有ato:xxxxx,xxxxx权限
	 *
	 * @param permissions 权限标识
	 * @return {boolean}
	 */
	public boolean hasAuthority(String... permissions) {
		if (ArrayUtil.isEmpty(permissions)) {
			return false;
		}
		Authentication authentication = SecurityUtils.getAuthentication();
		if (authentication == null) {
			return false;
		}
		Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
		return authorities.stream()
				.map(GrantedAuthority::getAuthority)
				.filter(StringUtils::hasText)
				.anyMatch(x -> PatternMatchUtils.simpleMatch(permissions, x));
	}
}
