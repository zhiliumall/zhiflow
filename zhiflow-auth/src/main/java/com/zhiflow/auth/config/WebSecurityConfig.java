package com.zhiflow.auth.config;

import org.springframework.security.config.Customizer;

import com.zhiflow.auth.core.BaseDaoAuthenticationProvider;
import com.zhiflow.auth.core.FormIdentityLoginConfigurer;
import com.zhiflow.common.security.component.SecurityNamespaceProperties;
import com.zhiflow.common.security.component.SecurityScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;

/**
 * @author 辰
 * 认证相关配置
 * @date 2023年10月27日10:03:25
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityNamespaceProperties.class)
public class WebSecurityConfig {


	/** 本服务的内部路径前缀（{@code zhiflow.path-prefix.value}）；空 = 未做前缀改造。 */
	@Value(SecurityScope.PATH_PREFIX_PLACEHOLDER)
	private String pathPrefix = "";

	/** 单体里认领自己那条命名空间用。多进程形态下这个列表是空的。 */
	private final SecurityNamespaceProperties securityNamespaces;

	public WebSecurityConfig(SecurityNamespaceProperties securityNamespaces) {
		this.securityNamespaces = securityNamespaces;
	}

	/**
	 * spring security 默认的安全策略
	 *
	 * <h2>{@code @Order(10)} 与单体里的圈范围</h2>
	 * <p><b>多进程形态下这两处都是恒等变换</b>：本服务只有三条链
	 * （授权服务器 {@code MIN_VALUE} → {@code resources} {@code @Order(0)} → 本链），
	 * 本链原先没有 {@code @Order}（即 {@code LOWEST_PRECEDENCE}）也是最后一条，
	 * 改成 {@code 10} 之后相对顺序<b>逐字不变</b>；而 {@code namespaces} 为空时
	 * 不会调用 {@code securityMatcher}，管辖范围仍是「任意请求」。
	 *
	 * <p><b>单体形态下这两处都是必需的</b>：
	 * <ul>
	 * <li>不圈范围 ⇒ 本链是 {@code anyRequest} 且排在资源服务器链之前，
	 *     会把 mall / upms / pay 的全部端点吞进 auth 的 formLogin 分支 —— 全站 302；</li>
	 * <li>不给明确 order ⇒ 本链与 {@code BaseResourceServerConfiguration} 的单体兜底链
	 *     （也是 {@code LOWEST_PRECEDENCE}）顺序不确定，
	 *     {@code WebSecurityFilterChainValidator} 有时判不可达、启动<b>随机</b>失败。
	 *     随机失败比稳定失败更糟。</li>
	 * </ul>
	 *
	 * @param http security注入点
	 * @return SecurityFilterChain
	 * @throws Exception
	 */
	@Bean
	@Order(10)
	SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
		this.securityNamespaces.findByBasePackage("com.zhiflow.auth")
				.ifPresent(ns -> {
					try {
						http.securityMatcher(SecurityScope.jurisdiction(ns.getValue()));
					}
					catch (Exception ex) {
						throw new IllegalStateException("auth 安全链圈管辖范围失败：" + ns, ex);
					}
				});
		// 避免iframe同源无法登录许iframe
		http.authorizeHttpRequests(authorizeRequests -> authorizeRequests.requestMatchers(permitAllPatterns())
				.permitAll()// 开放自定义的部分端点
				.anyRequest()
				.authenticated()).headers(header -> header.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
		).with(new FormIdentityLoginConfigurer(), Customizer.withDefaults()); // 表单登录个性化（Security 7：apply 已弃用，改 with）
		// 处理 UsernamePasswordAuthenticationToken
		http.authenticationProvider(new BaseDaoAuthenticationProvider());
		return http.build();
	}

	/**
	 * [P2.3-B] 放行清单必须跟着内部路径前缀走。
	 *
	 * <p>2026-08-15 实测：auth 开了 {@code /auth} 前缀之后 controller 的真实路径变成
	 * {@code /auth/token/**}，而这里写死的是 {@code /token/**} —— 规则不再匹配，
	 * <b>12 个端点全部落进默认的 authenticated + formLogin 分支变成 302</b>，
	 * 其中包含 {@code /token/logout}，登出直接失效。这就是当时回滚 auth 前缀的原因。
	 *
	 * <p>没配前缀时（今天）返回的清单与改造前逐字相同，是恒等变换；
	 * 配了前缀之后老路径那几条会失配但无害——迁移期的老路径由
	 * {@code LegacyPathPrefixFilter} 在安全链之前就改写成新路径了。
	 *
	 * <p>{@code /actuator/**} 不加前缀：它不是本服务 controller，不会被 {@code addPathPrefix} 改。
	 */
	private String[] permitAllPatterns() {
		List<String> patterns = new ArrayList<>(List.of("/token/**", "/actuator/**", "/token/*"));
		// 两种形态取前缀的方式不同，且**单体必须优先**：
		//   多进程 → 本服务自己的 zhiflow.path-prefix.value（= /auth）；
		//   单体   → 从 namespaces 认领 auth 那条。
		//
		// ⚠️ 为什么单体不能回落到单值：单体的 spring.config.import 会加载 7 个服务的 data-id，
		// **每一份里都有自己的 zhiflow.path-prefix.value**（/upms /auth /pay /weixin /live
		// /mall /mallapi），而 import 是后者覆盖前者 ⇒ 单值最终等于 import 列表里最后那个服务的前缀
		// （实测是 mall-api 的 /mallapi）。auth 若用它去算 permitAll 清单，/auth/token/** 整片失配，
		// 12 个 token 端点落进 formLogin 分支变 302（含 /token/logout，登出直接失效）——
		// 2026-08-16 单体实测复现，与此前一次事故同形态。
		String prefix = this.securityNamespaces.findByBasePackage("com.zhiflow.auth")
				.map(ns -> ns.getValue())
				.filter(SecurityScope::hasPrefix)
				.orElse(this.pathPrefix);
		if (SecurityScope.hasPrefix(prefix)) {
			String normalized = SecurityScope.normalizePrefix(prefix);
			patterns.add(normalized + "/token/**");
			patterns.add(normalized + "/token/*");
		}
		return patterns.toArray(new String[0]);
	}

	/**
	 * 暴露静态资源
	 *
	 * https://github.com/spring-projects/spring-security/issues/10938
	 * @param http
	 * @return
	 * @throws Exception
	 */
	@Bean
	@Order(0)
	SecurityFilterChain resources(HttpSecurity http) throws Exception {
		http.securityMatchers((matchers) -> matchers.requestMatchers("/actuator/**", "/css/**", "/error"))
				.authorizeHttpRequests((authorize) -> authorize.anyRequest().permitAll())
				.requestCache(RequestCacheConfigurer::disable)
				.securityContext(AbstractHttpConfigurer::disable)
				.sessionManagement(AbstractHttpConfigurer::disable);
		return http.build();
	}

}
