package com.zhiflow.common.security.component;

import com.zhiflow.common.core.constant.PathNamespace;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 安全链侧的多命名空间绑定。
 *
 * <h2>为什么这里要再绑定一次</h2>
 * <p>MVC 侧的绑定宿主是 common-data 的 {@code PathPrefixProperties}，而
 * <b>common-security 不依赖 common-data</b>（实测 pom 里没有这条依赖）。所以两侧只能各自
 * 绑定<b>同一段配置</b>，靠共享的结构类 {@link PathNamespace}（在两边都依赖的 common-core）
 * 保证不写岔。
 *
 * <p>这与 {@link SecurityScope#PATH_PREFIX_PROPERTY} 直接取 common-core 的常量是同一条纪律：
 * 口径只写一份。门禁 {@code SecurityScopeConsistencyTest} 守着属性名与绑定宿主一致。
 */
@ConfigurationProperties(prefix = "zhiflow.path-prefix")
public class SecurityNamespaceProperties {

	private List<PathNamespace> namespaces = new ArrayList<>();

	public List<PathNamespace> getNamespaces() {
		return this.namespaces;
	}

	public void setNamespaces(List<PathNamespace> namespaces) {
		this.namespaces = namespaces;
	}

	/** 是否是单体的多命名空间形态。判据与 {@code PathPrefixConstants.MULTI_NAMESPACE_CONDITION} 一致。 */
	public boolean isMultiNamespace() {
		return this.namespaces != null && this.namespaces.stream().anyMatch(PathNamespace::hasValue);
	}

	/** 指定认证模式下的全部命名空间（{@code api-login} 之外的一律按 {@code oauth2}）。 */
	public List<PathNamespace> byMode(boolean apiLogin) {
		if (this.namespaces == null) {
			return List.of();
		}
		return this.namespaces.stream()
				.filter(PathNamespace::hasValue)
				.filter(ns -> SecurityScope.isApiLoginMode(ns.getAuthenticationMode()) == apiLogin)
				.toList();
	}

	/**
	 * 一组命名空间的合并管辖范围：每个前缀都取 {@code /x} 与 {@code /x/**} 两条。
	 *
	 * <p>为空时返回一个<b>永不匹配</b>的 matcher，而不是「匹配一切」——
	 * 后者会让一条本该空转的链吞掉全部请求。这是本方法唯一容易写错、且错了最致命的地方。
	 */
	public static RequestMatcher jurisdictionOf(List<PathNamespace> group) {
		List<RequestMatcher> matchers = new ArrayList<>();
		for (PathNamespace ns : group) {
			matchers.add(SecurityScope.jurisdiction(ns.getValue()));
		}
		if (matchers.isEmpty()) {
			return request -> false;
		}
		return new OrRequestMatcher(matchers);
	}

	/**
	 * 按 controller 包前缀认领自己那条命名空间。
	 *
	 * <p>给<b>自带安全链</b>的服务用（目前只有 auth）：它在单体里必须把自己的链圈到
	 * {@code /auth/**}，否则那条 {@code anyRequest} 的 formLogin 链会把整个单体吞掉。
	 */
	public Optional<PathNamespace> findByBasePackage(String basePackagePrefix) {
		if (this.namespaces == null) {
			return Optional.empty();
		}
		return this.namespaces.stream()
				.filter(PathNamespace::hasValue)
				.filter(ns -> ns.getBasePackage() != null && !ns.getBasePackage().isBlank())
				.filter(ns -> ns.getBasePackage().startsWith(basePackagePrefix))
				.findFirst();
	}
}
