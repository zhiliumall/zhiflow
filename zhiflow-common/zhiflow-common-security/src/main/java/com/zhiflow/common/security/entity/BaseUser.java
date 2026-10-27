package com.zhiflow.common.security.entity;

import lombok.Getter;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

import java.io.Serial;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author
 * 扩展用户信息
 */
public class BaseUser extends User  implements OAuth2AuthenticatedPrincipal {
    @Serial
    private static final long serialVersionUID = 1L;
	/**
	 * 用户ID
	 */
	@Getter
	private String id;
	/**
	 * 机构ID
	 */
	@Getter
	private String organId;

	/**
	 * 账号类型：-1、系统管理员；1、商城管理员
	 */
	@Getter
	private String type;

	/**
	 * 租户ID
	 */
	@Getter
	private String tenantId;

	/**
	 * 平台系统管理员绑定的租户
	 */
	@Getter
	private List<String> tenantIds;

	/**
	 * 扩展属性，方便存放oauth 上下文相关信息
	 */
	private final Map<String, Object> attributes = new HashMap<>();
	/**
	 * Construct the <code>User</code> with the details required by
	 * {@link DaoAuthenticationProvider}.
	 *
	 * @param id                    用户ID
	 * @param organId                机构ID
	 * @param tenantId              租户ID
	 * @param username              the username presented to the
	 *                              <code>DaoAuthenticationProvider</code>
	 * @param password              the password that should be presented to the
	 *                              <code>DaoAuthenticationProvider</code>
	 * @param enabled               set to <code>true</code> if the user is enabled
	 * @param accountNonExpired     set to <code>true</code> if the account has not expired
	 * @param credentialsNonExpired set to <code>true</code> if the credentials have not
	 *                              expired
	 * @param accountNonLocked      set to <code>true</code> if the account is not locked
	 * @param authorities           the authorities that should be granted to the caller if they
	 *                              presented the correct username and password and the user is enabled. Not null.
	 * @throws IllegalArgumentException if a <code>null</code> value was passed either as
	 *                                  a parameter or as an element in the <code>GrantedAuthority</code> collection
	 */
	public BaseUser(String id, String organId, String type, String tenantId, String username, String password, List<String> tenantIds,
					boolean enabled, boolean accountNonExpired, boolean credentialsNonExpired, boolean accountNonLocked, Collection<? extends GrantedAuthority> authorities) {
		super(username, password, enabled, accountNonExpired, credentialsNonExpired, accountNonLocked, authorities);
		this.id = id;
		this.organId = organId;
		this.type = type;
		this.tenantId = tenantId;
		this.tenantIds = tenantIds;
	}

	/**
	 * Get the OAuth 2.0 token attributes
	 * @return the OAuth 2.0 token attributes
	 */
	@Override
	public Map<String, Object> getAttributes() {
		return this.attributes;
	}

	@Override
	public String getName() {
		return this.getUsername();
	}
}
