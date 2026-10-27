package com.zhiflow.boot.edge;

import cloud.tianai.captcha.cache.CacheStore;
import tools.jackson.databind.ObjectMapper;
import com.zhiflow.common.core.constant.PathPrefixConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 网关承重逻辑的单体版注册处（单体去掉网关的前提）。
 *
 * <h2>⭐ 多进程零变化（与 {@code BaseResourceServerConfiguration} 同一套机制）</h2>
 * <p>整个配置类挂 {@link PathPrefixConstants#MULTI_NAMESPACE_CONDITION}：
 * 多进程形态（每个服务一个进程）从不配置
 * {@code zhiflow.path-prefix.namespaces}，这里**一个 bean 都不注册** ——
 * 「零变化」由「新代码根本不执行」保证，而不是逻辑等价论证。此外这批类只存在于
 * zhiflow-boot 工件里，per-service jar 连 class 都没有，双保险。
 * 机器判据：{@code GatewayParityRegistrationTest}。
 *
 * <h2>⚠️ 逃生舱 {@code zhiflow.monolith.gateway-parity.enabled}（默认 true）</h2>
 * <p>这批 filter 与**前置网关互斥**：网关已消费掉一次性滑块令牌，
 * boot 再做一遍就是「令牌已核销 → 428」，登录必断。两种场景要关它：
 * <ul>
 * <li><b>形态二</b>（boot 拆分多进程 + 网关）：各拆分进程会配自己的
 *     namespaces，条件为真 —— 但网关还在前面，且拆分进程之间的契约 HTTP 调用带
 *     {@code from: Y}，from 剥离会把 142 个 {@code @Inside} 端点打成 403。
 *     形态二的 data-id 必须显式置 {@code false}（失败模式是**响亮的**登录/内调 403，
 *     冒烟当场能抓到，不是静默漏洞 —— 这是默认值取 true 而不是 false 的原因：
 *     反过来默认 false，交付忘配的失败模式是 from 头可伪造，**静默**）；</li>
 * <li>过渡期单体 + 网关同栈（当前 compose 还没去网关就先发了本代码）：同理先置
 *     {@code false}，去网关那次改回（或删掉该键）。</li>
 * </ul>
 *
 * <h2>顺序表（都在 servlet 过滤器链上，越小越先）</h2>
 * <pre>
 * MIN_VALUE      FromHeaderStrippingFilter   ← 必须最早：安全链/MVC/一切读头方之前
 * MIN_VALUE+1    BlockedPathsFilter          （网关序：strip(-1000) → 恶意路径(-200)）
 * MIN_VALUE+10   ValidateCodeServletFilter   （网关路由 filters 列表序：验证码在前）
 * MIN_VALUE+50   TenantContextHolderFilter   （既有，不归本类管，列出只为看清相对位置）
 * -100           springSecurityFilterChain   （SecurityProperties.DEFAULT_FILTER_ORDER）
 * </pre>
 * 验证码只对 {@code /oauth2/token} 生效，放在租户过滤器之前与网关拓扑一致
 * （网关先做完才转发，服务侧租户过滤器在其后）。
 *
 * <p>原网关的第四件「登录密码 AES 解密」开源版不要：前端写死的密钥每个部署都一样，等于没有加密，
 * 密码传输靠 TLS。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnExpression("${zhiflow.monolith.gateway-parity.enabled:true} and ("
		+ PathPrefixConstants.MULTI_NAMESPACE_CONDITION + ")")
@EnableConfigurationProperties(GatewayParityProperties.class)
public class GatewayParityConfiguration {

	public static final int FROM_STRIP_ORDER = Ordered.HIGHEST_PRECEDENCE;

	public static final int BLOCKED_PATHS_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

	public static final int VALIDATE_CODE_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

	@Bean
	public FilterRegistrationBean<FromHeaderStrippingFilter> fromHeaderStrippingFilter() {
		log.info("[gateway-parity] 注册 from 头剥离 filter（@Inside 边界的不可伪造性来源）");
		FilterRegistrationBean<FromHeaderStrippingFilter> registration = new FilterRegistrationBean<>(
				new FromHeaderStrippingFilter());
		registration.setOrder(FROM_STRIP_ORDER);
		registration.addUrlPatterns("/*");
		// ERROR/ASYNC 派发会拿到**原始**容器请求（未包装），只注册 REQUEST 的话
		// 错误管道里 from 头会重新可见 —— 与 springSecurityFilterChain 同口径全覆盖。
		registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ERROR,
				jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.FORWARD,
				jakarta.servlet.DispatcherType.INCLUDE);
		registration.setName("fromHeaderStrippingFilter");
		return registration;
	}

	@Bean
	public FilterRegistrationBean<BlockedPathsFilter> blockedPathsFilter() {
		log.info("[gateway-parity] 注册恶意路径拦截 filter（网关 SecurityGlobalFilter 对应）");
		FilterRegistrationBean<BlockedPathsFilter> registration = new FilterRegistrationBean<>(
				new BlockedPathsFilter());
		registration.setOrder(BLOCKED_PATHS_ORDER);
		registration.addUrlPatterns("/*");
		registration.setName("blockedPathsFilter");
		return registration;
	}

	@Bean
	public FilterRegistrationBean<ValidateCodeServletFilter> validateCodeServletFilter(
			GatewayParityProperties configProperties, ObjectMapper objectMapper,
			// 与网关同款注入：主 redisTemplate（String key 序列化，与短信验证码写入方同一个 bean）
			@Qualifier("redisTemplate") RedisTemplate<String, Object> redisTemplate, CacheStore cacheStore) {
		log.info("[gateway-parity] 注册登录验证码校验 filter，ignore-clients={}", configProperties.getIgnoreClients());
		FilterRegistrationBean<ValidateCodeServletFilter> registration = new FilterRegistrationBean<>(
				new ValidateCodeServletFilter(configProperties, objectMapper, redisTemplate, cacheStore));
		registration.setOrder(VALIDATE_CODE_ORDER);
		registration.addUrlPatterns("/*");
		registration.setName("validateCodeServletFilter");
		return registration;
	}

}
