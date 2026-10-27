package com.zhiflow.common.security.component;

import cn.hutool.extra.spring.SpringUtil;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.common.security.service.BaseUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.Principal;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * @author 辰
 * @date 2023年10月26日17:16:24
 *
 * <h2>压测优化（2026-09-30）</h2>
 * <ul>
 * <li><b>A1（始终生效，语义零变化）</b>：租户过滤器已经在同一个请求里读过这个令牌的授权记录时直接复用
 *     （{@link PreloadedAuthorization}，令牌值必须逐字相等），不再第二次 GET + JDK 反序列化约 34 KB。</li>
 * <li><b>A2（开关 {@value AuthorizationSnapshotCache#ENABLED_PROPERTY}，默认关）</b>：命中进程内缓存时
 *     直接返回缓存的 principal，每请求只剩一次 {@code EXISTS}；语义边界见 {@link AuthorizationSnapshotCache}。
 *     开关关着时这里的行为与 A1 完全相同。</li>
 * </ul>
 */
@Slf4j
public class BaseCustomOpaqueTokenIntrospector implements OpaqueTokenIntrospector {

	private final OAuth2AuthorizationService authorizationService;

	/** 压测优化 A2 的内省缓存；null = 本进程没有（auth，或测试）。是否启用另由开关每请求现读。 */
	@Nullable
	private final AuthorizationSnapshotCache snapshotCache;

	public BaseCustomOpaqueTokenIntrospector(OAuth2AuthorizationService authorizationService) {
		this(authorizationService, null);
	}

	public BaseCustomOpaqueTokenIntrospector(OAuth2AuthorizationService authorizationService,
			@Nullable AuthorizationSnapshotCache snapshotCache) {
		this.authorizationService = authorizationService;
		this.snapshotCache = snapshotCache;
	}

	@Override
	public OAuth2AuthenticatedPrincipal introspect(String token) {
		// [A1] 租户过滤器在本请求里对同一个令牌查过的结果（没有 / 令牌值不同则为 null）
		HttpServletRequest request = currentRequest();
		PreloadedAuthorization preloaded = PreloadedAuthorization.find(request, token);

		// [A2] 进程内缓存：命中且令牌仍在（本请求已知的结果，否则 EXISTS）就直接返回
		boolean snapshotEnabled = this.snapshotCache != null && this.snapshotCache.isEnabled();
		String snapshotKey = null;
		long snapshotEpoch = 0L;
		if (snapshotEnabled) {
			snapshotKey = AuthorizationSnapshotCache.keyOf(token);
			AuthorizationSnapshotCache.Snapshot hit = this.snapshotCache.getIfAlive(snapshotKey, token,
					preloaded == null ? null : preloaded.exists());
			if (hit != null) {
				return hit.principal();
			}
			snapshotEpoch = this.snapshotCache.epoch();
		}

		OAuth2Authorization oldAuthorization;
		if (preloaded != null && preloaded.isLoaded()) {
			oldAuthorization = preloaded.getAuthorization();
			if (oldAuthorization != null) {
				// 用过就把授权记录这个大对象从 request 上放掉（原先它在过滤器里用完即成垃圾），只留「已确认存在」
				PreloadedAuthorization.verifiedExisting(request, token);
			}
		}
		else {
			oldAuthorization = authorizationService.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
		}
		if (Objects.isNull(oldAuthorization)) {
			throw new InvalidBearerTokenException(token);
		}

		// 客户端模式：把 OAuth2Authorization 的已授权 scopes 映射成 SCOPE_xxx GrantedAuthority，
		// 让 @PreAuthorize("hasAuthority('SCOPE_xxx')") 在 resource server 端可用。
		if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(oldAuthorization.getAuthorizationGrantType())) {
			java.util.Set<String> authorizedScopes = oldAuthorization.getAuthorizedScopes();
			java.util.List<org.springframework.security.core.GrantedAuthority> scopeAuthorities = (authorizedScopes == null)
					? java.util.Collections.emptyList()
					: authorizedScopes.stream()
							.map(s -> (org.springframework.security.core.GrantedAuthority) new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_" + s))
							.toList();
			return new BaseClientCredentialsOAuth2AuthenticatedPrincipal(oldAuthorization.getAttributes(),
					scopeAuthorities, oldAuthorization.getPrincipalName());
		}

		Map<String, BaseUserDetailsService> userDetailsServiceMap = SpringUtil
			.getBeansOfType(BaseUserDetailsService.class);

		Optional<BaseUserDetailsService> optional = userDetailsServiceMap.values()
			.stream()
			.filter(service -> service.support(Objects.requireNonNull(oldAuthorization).getRegisteredClientId(),
					oldAuthorization.getAuthorizationGrantType().getValue()))
			.max(Comparator.comparingInt(Ordered::getOrder));

		UserDetails userDetails;
		try {
			Object principal = Objects.requireNonNull(oldAuthorization).getAttributes().get(Principal.class.getName());
			UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken = (UsernamePasswordAuthenticationToken) principal;
			Object tokenPrincipal = usernamePasswordAuthenticationToken.getPrincipal();
			userDetails = optional.get().loadUserByUser((BaseUser) tokenPrincipal);
		}
		catch (UsernameNotFoundException notFoundException) {
			log.warn("用户不存在 {}", notFoundException.getLocalizedMessage());
			throw notFoundException;
		}
		catch (Exception ex) {
			// 不要吞掉异常再用 null userDetails 触发 NPE — 包成 InvalidBearerTokenException 让上层正确返回 401
			log.error("资源服务器 introspect Token error", ex);
			throw new InvalidBearerTokenException("introspect token failed: " + ex.getMessage(), ex);
		}

		// 注入客户端信息，方便上下文中获取
		BaseUser oAuthUser = (BaseUser) userDetails;
		oAuthUser.getAttributes().put(SecurityConstants.CLIENT_ID, oldAuthorization.getRegisteredClientId());
		// [A2] 被锁的不缓存：与 user_cache 自己「未锁定才写入」的策略一致（BaseUserDetailsServiceImpl）
		if (snapshotEnabled && oAuthUser.isAccountNonLocked()) {
			rememberSnapshot(snapshotKey, snapshotEpoch, oldAuthorization, oAuthUser);
		}
		return oAuthUser;
	}

	/** 放进 A2 缓存；缓存本身出任何问题都只记日志，绝不让这个请求因此失败。 */
	private void rememberSnapshot(String key, long epochAtLoad, OAuth2Authorization authorization, BaseUser principal) {
		try {
			this.snapshotCache.put(key, epochAtLoad, TokenTenant.from(authorization), principal);
		}
		catch (RuntimeException ex) {
			log.warn("内省缓存写入失败，本次不缓存: {}", ex.toString());
		}
	}
	@Nullable
	private static HttpServletRequest currentRequest() {
		var attrs = RequestContextHolder.getRequestAttributes();
		return (attrs instanceof ServletRequestAttributes sra) ? sra.getRequest() : null;
	}

}
