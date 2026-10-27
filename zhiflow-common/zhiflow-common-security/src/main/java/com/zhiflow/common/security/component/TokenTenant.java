package com.zhiflow.common.security.component;

import com.zhiflow.common.security.entity.BaseUser;
import org.springframework.lang.Nullable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 令牌里签发时那个用户的租户三元组 —— {@code TenantContextHolderFilter} 算租户只需要这三样。
 *
 * <p>只存「令牌里的原始值」，<b>不存算出来的租户</b>：超管每个请求的 {@code switch-tenant-id} 头都可以不同，
 * 切租户必须每请求现算（见过滤器）。令牌的授权记录在它的生命周期里不会变，所以按令牌缓存这三样是安全的，
 * 令牌是否仍有效另由每请求一次的 {@code EXISTS} 判定。
 *
 * @param tenantId 令牌用户的租户
 * @param type 令牌用户的类型（-1 系统管理员 / 1 租户管理员 / …），原样保存，比较时由调用方 {@code StrUtil.toString}
 * @param tenantIds 平台管理员绑定的租户（null 与空列表语义不同，原样保留；非 null 时存只读副本）
 */
public record TokenTenant(@Nullable String tenantId, @Nullable String type, @Nullable List<String> tenantIds) {

	/**
	 * 从授权记录里取；记录里没有 principal 时返回 null。
	 *
	 * <p>取法与强转与改造前 {@code TenantContextHolderFilter} 逐字相同（principal 不是
	 * {@link UsernamePasswordAuthenticationToken} / 里面不是 {@link BaseUser} 时照样抛同样的异常），
	 * 这样过滤器改成走本方法后，异常形态也不变。
	 */
	@Nullable
	public static TokenTenant from(OAuth2Authorization authorization) {
		UsernamePasswordAuthenticationToken principal = (UsernamePasswordAuthenticationToken) authorization
			.getAttributes()
			.get(Principal.class.getName());
		if (principal == null) {
			return null;
		}
		BaseUser baseUser = (BaseUser) principal.getPrincipal();
		List<String> tenantIds = baseUser.getTenantIds();
		return new TokenTenant(baseUser.getTenantId(), baseUser.getType(),
				tenantIds == null ? null : Collections.unmodifiableList(new ArrayList<>(tenantIds)));
	}

}
