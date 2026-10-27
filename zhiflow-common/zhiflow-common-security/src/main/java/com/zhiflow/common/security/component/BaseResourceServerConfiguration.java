package com.zhiflow.common.security.component;

import com.zhiflow.common.core.constant.PathNamespace;
import com.zhiflow.common.core.constant.PathPrefixConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 资源服务器认证授权配置。
 *
 * <h2>[P2.3-B] 管辖范围按路径前缀圈定，认证机制按配置声明</h2>
 * <p>改造前这里是按 {@code spring.application.name} 做字符串分支的
 * （{@code "zhiflow-mall-api".equals(appName)} → 全放行），并且只有一条
 * 「匹配任意请求」的链。两个口径现在都显式化了，理由见 {@link SecurityScope}。
 *
 * <h2>为什么是两条链而不是一条</h2>
 * <p>加了前缀之后，本服务 controller 的路径都在 {@code /<前缀>} 之下，但进程里<b>还有</b>
 * 一批不归 controller 管、也不会被加前缀的路径：{@code /actuator/**}、{@code /error}、
 * {@code /v3/api-docs*}、静态资源 {@code /webjars/**}、WebSocket
 * （weixin 的 {@code /ws/**}、im 的 {@code /im/ws}）。实测：每个已加前缀的服务，
 * 前缀之外都有 59~60 条这样的映射。
 *
 * <p>如果只留一条「按前缀圈范围」的链，这些路径就<b>匹配不到任何链</b>——
 * 而 Spring Security 对匹配不到链的请求是<b>完全不过安全过滤器</b>的，
 * 等于把它们从「白名单放行」悄悄换成「根本没有安全链」。对 actuator 这类本来就在
 * {@code DEFAULT_IGNORE_URLS} 里的路径结果相同，对 WebSocket 端点则是实打实的鉴权丢失。
 *
 * <p>所以第二条链兜住前缀之外的一切，<b>规则与第一条完全相同</b>：
 * P2.3-B 这一步只改变「谁归谁管」，不改变「怎么管」。行为零变化，
 * 由权限矩阵 2820 行 diff = 0 佐证。
 *
 * <p>⚠️ 兜底链只在配置了前缀时才注册。没配前缀时第一条链本身就是「任意请求」，
 * 再注册一条同样是「任意请求」的链会被 {@code WebSecurityFilterChainValidator} 判为
 * 不可达（{@code UnreachableFilterChainException}）而<b>启动失败</b>。
 *
 * @author 辰
 * @date 2023年10月26日17:20:42
 */
@Slf4j
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@EnableConfigurationProperties(SecurityNamespaceProperties.class)
public class BaseResourceServerConfiguration {

	protected final ResourceAuthExceptionEntryPoint resourceAuthExceptionEntryPoint;

	private final PermitAllUrlProperties permitAllUrl;

	private final BaseBearerTokenExtractor baseBearerTokenExtractor;

	private final OpaqueTokenIntrospector customOpaqueTokenIntrospector;

	/** 本服务的内部路径前缀；空 = 未做前缀改造，本链管辖全部请求。 */
	@Value(SecurityScope.PATH_PREFIX_PLACEHOLDER)
	private String pathPrefix = "";

	/** 本服务命名空间的认证机制，见 {@link SecurityScope#MODE_API_LOGIN}。 */
	@Value(SecurityScope.AUTHENTICATION_MODE_PLACEHOLDER)
	private String authenticationMode = SecurityScope.MODE_OAUTH2;

	// ==================================================================================
	// 单体（形态一 all）的多命名空间安全链
	//
	// 单体里 8 个服务同进程，需要 /mallapi/** 走 api-login、其余走 oauth2，而
	// pathPrefix / authenticationMode 是「一个进程一份」。下面三条 @Bean 只在配了
	// zhiflow.path-prefix.namespaces 时注册 —— 多进程形态一个都不注册，走的还是
	// 本类下半部分那两条老链，**一行新代码都不执行**。「多进程零变化」由此保证，
	// 而不是靠逻辑等价论证。
	//
	// ⚠️ order 分层（全局唯一一份，改任何一个都要重看这张表）：
	//   MIN_VALUE  auth 的授权服务器链（/oauth2/** 过滤器端点，必须最先）
	//   0          auth 的 resources 链（/actuator/** /css/** /error）
	//   10         auth 的 default 链（单体里圈 /auth/**；多进程里它本来就是最后一条）
	//   100        本类：api-login 命名空间（mall-api）
	//   110        本类：oauth2 命名空间（mall-admin / upms / pay / live / weixin / im）
	//   LOWEST     本类：兜底（前缀之外的 actuator / swagger / 静态资源 / WebSocket）
	//
	// 兜底链必须是**唯一**一条 anyRequest 且排在最后，否则 WebSecurityFilterChainValidator
	// 判前面那条不可达、直接启动失败。2026-08-16 单体冒烟实测到的就是这个
	// （Base 的 outsidePrefix 与 auth 的 default 两条 anyRequest 撞车）。
	// ==================================================================================

	/** api-login 命名空间（C 端门户）：Security 层放行，认证交给 MVC 层的 {@code @ApiLogin}。 */
	@Bean
	@Order(100)
	@ConditionalOnExpression(PathPrefixConstants.MULTI_NAMESPACE_CONDITION)
	SecurityFilterChain apiLoginNamespacesFilterChain(HttpSecurity http,
			SecurityNamespaceProperties namespaces) throws Exception {
		List<PathNamespace> group = namespaces.byMode(true);
		log.info("[security-scope] 单体 api-login 命名空间：{}", group);
		http.securityMatcher(SecurityNamespaceProperties.jurisdictionOf(group));
		return buildApiLogin(http);
	}

	/** oauth2 命名空间：{@code releaseUrls} 放行，其余要 Bearer token。 */
	@Bean
	@Order(110)
	@ConditionalOnExpression(PathPrefixConstants.MULTI_NAMESPACE_CONDITION)
	SecurityFilterChain oauth2NamespacesFilterChain(HttpSecurity http,
			SecurityNamespaceProperties namespaces) throws Exception {
		List<PathNamespace> group = namespaces.byMode(false);
		log.info("[security-scope] 单体 oauth2 命名空间：{}", group);
		http.securityMatcher(SecurityNamespaceProperties.jurisdictionOf(group));
		return buildOauth2(http, prefixesOf(group));
	}

	/**
	 * 单体的兜底链：全部命名空间之外的一切（actuator / swagger / 静态资源 / WebSocket）。
	 *
	 * <p>规则取 {@code oauth2}（更严的一边）。不能不注册它 —— Spring Security 对
	 * <b>匹配不到任何链</b>的请求是完全不过安全过滤器的，等于把这些路径从「白名单放行」
	 * 悄悄换成「根本没有安全链」，而 WebSocket 端点（weixin 的 {@code /ws/**}、
	 * im 的 {@code /im/ws}）属于实打实的鉴权丢失。
	 *
	 * <p>白名单口径与 oauth2 命名空间链<b>完全相同</b>（含按命名空间补出来的那批
	 * {@code /<前缀>/ai/**}）—— 那批在这条链上全是死模式（带前缀的路径归上一条链管），
	 * 传同一份只是为了「两条链规则逐字相同」这句话继续为真。
	 */
	@Bean
	@Order(Ordered.LOWEST_PRECEDENCE)
	@ConditionalOnExpression(PathPrefixConstants.MULTI_NAMESPACE_CONDITION)
	SecurityFilterChain monolithFallbackFilterChain(HttpSecurity http,
			SecurityNamespaceProperties namespaces) throws Exception {
		log.info("[security-scope] 单体兜底链（命名空间之外的一切），规则 = oauth2");
		return buildOauth2(http, prefixesOf(namespaces.byMode(false)));
	}

	private static List<String> prefixesOf(List<PathNamespace> group) {
		return group.stream().map(PathNamespace::getValue).toList();
	}

	// ==================================================================================
	// 多进程形态的两条链（P2.3-B 立的）。多命名空间时不注册。
	// ==================================================================================

	/**
	 * 本服务命名空间的安全链。管辖范围 = 内部路径前缀；没配前缀时 = 全部请求（改造前的形态）。
	 */
	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	@ConditionalOnExpression(PathPrefixConstants.SINGLE_NAMESPACE_CONDITION)
	SecurityFilterChain servicePrefixSecurityFilterChain(HttpSecurity http) throws Exception {
		if (SecurityScope.hasPrefix(this.pathPrefix)) {
			http.securityMatcher(SecurityScope.jurisdiction(this.pathPrefix));
			log.info("[security-scope] 安全链管辖范围 = {}/**，认证机制 = {}",
					SecurityScope.normalizePrefix(this.pathPrefix), this.authenticationMode);
		}
		else {
			log.info("[security-scope] 本服务未配置内部路径前缀，安全链管辖全部请求，认证机制 = {}",
					this.authenticationMode);
		}
		return build(http);
	}

	/**
	 * 前缀之外的兜底链（actuator / swagger / 静态资源 / WebSocket）。规则与上面那条完全一致。
	 *
	 * <p>只在配置了前缀时注册，否则会与上面那条「任意请求」的链撞车导致启动失败。
	 * 条件表达式与 {@link SecurityScope#hasPrefix} 必须给出同一个答案——
	 * 用 {@code @ConditionalOnProperty} 的话，显式配成空串会被判为「有值」，两者就会不一致。
	 */
	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE + 10)
	@ConditionalOnExpression(SecurityScope.HAS_PREFIX_CONDITION + " and "
			+ PathPrefixConstants.SINGLE_NAMESPACE_CONDITION)
	SecurityFilterChain outsidePrefixSecurityFilterChain(HttpSecurity http) throws Exception {
		return build(http);
	}

	/**
	 * 两条链共用的规则。
	 *
	 * <p>{@code api-login} 模式：Spring Security 层全放行，认证由
	 * {@code ThirdSessionInterceptor} + {@code @ApiLogin} 在 MVC 层做。
	 * 其余（{@code oauth2}）：{@code releaseUrls} 放行，其余要 Bearer token。
	 */
	private SecurityFilterChain build(HttpSecurity http) throws Exception {
		if (SecurityScope.isApiLoginMode(this.authenticationMode)) {
			return buildApiLogin(http);
		}
		return buildOauth2(http, this.pathPrefix);
	}

	/** C 端门户规则：Security 层全放行，认证在 MVC 层。单体与多进程共用同一份实现。 */
	private SecurityFilterChain buildApiLogin(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
			.headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable))
			.csrf(AbstractHttpConfigurer::disable);
		return http.build();
	}

	/**
	 * 资源服务器规则（多进程形态：本服务只有一个前缀）。
	 *
	 * @param pathPrefix 给写死的 {@code /ai/**} 补前缀用；空串 = 没做前缀改造，补出来就是裸的
	 * {@code /ai/**}（改造前的形态，逐字不变）
	 */
	private SecurityFilterChain buildOauth2(HttpSecurity http, String pathPrefix) throws Exception {
		// 刻意用 Collections.singletonList 而不是 List.of：pathPrefix 允许是 null，
		// 而 List.of(null) 直接 NPE；normalizePrefix(null) 本来就有定义（= 空前缀）。
		return buildOauth2(http, Collections.singletonList(pathPrefix));
	}

	/**
	 * 资源服务器规则：{@code releaseUrls} 放行，其余要 Bearer token。
	 *
	 * @param pathPrefixes 本链管辖的全部命名空间前缀。多进程形态是一个（可能为空串），
	 * 单体形态是该链圈住的那一组 —— 写死的 {@code /ai/**} 按每个前缀各补一条，
	 * 见 {@link #aiPermitAllPatterns(List)}
	 */
	private SecurityFilterChain buildOauth2(HttpSecurity http, List<String> pathPrefixes) throws Exception {
		List<String> allUrls = new ArrayList<>(this.permitAllUrl.getReleaseUrls());
		for (String pattern : aiPermitAllPatterns(pathPrefixes)) {
			if (!allUrls.contains(pattern)) {
				allUrls.add(pattern);
			}
		}

		// Security 7 移除了 AntPathRequestMatcher；PathPatternRequestMatcher 要求 pattern 以 "/" 开头
		PathPatternRequestMatcher.Builder matcherBuilder = PathPatternRequestMatcher.withDefaults();
		RequestMatcher[] requestMatchers = allUrls.stream()
			.map(pattern -> pattern.startsWith("/") ? pattern : "/" + pattern)
			.map(matcherBuilder::matcher)
			.toArray(RequestMatcher[]::new);

		http
			.authorizeHttpRequests(
					authorizeRequests -> authorizeRequests.requestMatchers(requestMatchers).permitAll().anyRequest().authenticated())
			.oauth2ResourceServer(oauth2 -> oauth2.opaqueToken(token -> token.introspector(this.customOpaqueTokenIntrospector))
				.authenticationEntryPoint(this.resourceAuthExceptionEntryPoint)
				.bearerTokenResolver(this.baseBearerTokenExtractor))
			.headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable))
			.csrf(AbstractHttpConfigurer::disable);

		return http.build();
	}

	/**
	 * 写死的 {@code /ai/**} 白名单，<b>按命名空间各补一条</b>。
	 *
	 * <h2>这条为什么必须存在（不是可有可无的样式问题）</h2>
	 * <p>它是 mall-admin 的 {@code /ai/health/profile}（{@code AiHealthController}）唯一的
	 * permitAll 来源：那个 controller 既没有 {@code @Inside} 也没有 {@code @ApiLogin}，
	 * 所以 {@code PermitAllUrlProperties} 从 handler 映射<b>现算</b>的那部分根本不会收录它；
	 * mall-admin 自己的 data-id 里 {@code release-urls} 也没有任何 {@code /ai} 条目
	 * （共享的 {@code application-dev.yml} 里那条是<b>裸的</b> {@code /ai/**}，加了
	 * {@code /mall} 前缀之后同样匹配不上）。2026-08-16 实测佐证：
	 * 匿名 GET {@code :6003/mall/ai/health/profile} = <b>200</b>，
	 * 对照组 {@code /mall/goodsspu/page} = <b>424</b>（要令牌）。
	 * 也就是说，删掉或算错这一行的表现是那个端点<b>从匿名可达变成 424</b>。
	 *
	 * <h2>为什么单体不能只补一条</h2>
	 * <p>单体里 oauth2 链一条管 N 个命名空间，补哪一个前缀都只能覆盖 N 分之一；
	 * 而补<b>裸的</b> {@code /ai/**}（此前的做法）在单体里更糟 —— 全仓真实路径都是
	 * {@code /mall/ai/**} 或 {@code /mallapi/ai/**}（按 {@code /actuator/mappings} 现拉），
	 * 裸模式一条也匹配不上（<b>净效果 = mall-admin 那个端点 424</b>），
	 * 同时它还给「命名空间之外的 {@code /ai/...}」白送一个 permitAll，是一次纯粹的放宽。
	 *
	 * <p>所以按每个前缀各补一条：得到的白名单与「N 个进程各自算一份」<b>逐条相同</b> ——
	 * 单体的正确性判据从来是「与多进程等价」，不多不少正好是这个集合。
	 * 多进程形态走的是 {@link #buildOauth2(HttpSecurity, String)} 那个单值重载，
	 * 传进来的还是本服务那一个前缀，结果与改造前<b>逐字相同</b>。
	 *
	 * <p>⚠️ 这条口径此前一度被复制进外部配置的 {@code release-urls}（那条 {@code /mall/ai/**}）。
	 * 配置里那份是<b>冗余</b>而非依赖：配置没跟着同步时没人会记得去改它，漏了是静默的 424。口径回到代码里。
	 *
	 * @param pathPrefixes 本链管辖的命名空间前缀集合；空集合 = 一条都不补
	 */
	static List<String> aiPermitAllPatterns(List<String> pathPrefixes) {
		return pathPrefixes.stream()
			.map(SecurityScope::normalizePrefix)
			.distinct()
			.map(prefix -> prefix + "/ai/**")
			.toList();
	}

}
