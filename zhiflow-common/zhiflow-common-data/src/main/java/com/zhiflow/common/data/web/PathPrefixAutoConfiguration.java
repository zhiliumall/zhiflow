package com.zhiflow.common.data.web;

import com.zhiflow.common.core.constant.PathNamespace;
import com.zhiflow.common.core.constant.PathPrefixConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * [P2.3-A] 按包名给本服务的 controller 挂内部路径前缀。
 *
 * <p>八个服务共用这一份实现，各自只需两行配置：
 * <pre>
 * zhiflow:
 *   path-prefix:
 *     value: /pay
 *     base-package: com.zhiflow.pay.admin.controller
 * </pre>
 *
 * <p>不写 {@code value} 就完全不装配（默认），所以这份代码可以先合进 master、
 * 再逐个服务按 P2.3 规定的顺序（pay/live → auth/upms/weixin → mall 对）打开，
 * <b>合代码与改行为解耦</b>。
 *
 * <p>配套的外部 URL 不变靠网关路由加 {@code PrefixPath=<value>}；
 * 迁移期靠 {@link LegacyPathPrefixFilter} 同时接受老路径，做到发版零窗口。
 * 验收工具两件：{@code tools/url-invariance}（四端 URL 逐字节不变）与
 * {@code tools/authz-matrix}（改 SERVICES 表第三列后 diff 仍应为 0）。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(PathPrefixProperties.class)
@ConditionalOnExpression("!'" + PathPrefixConstants.PLACEHOLDER + "'.isBlank() or "
		+ PathPrefixConstants.MULTI_NAMESPACE_CONDITION)
public class PathPrefixAutoConfiguration {

	@Bean
	public WebMvcConfigurer pathPrefixWebMvcConfigurer(PathPrefixProperties properties) {
		// 启动即自述：加前缀这件事一旦没生效，表现是「外部一切正常、阶段 3 才炸」，
		// 是最难在当下发现的一类哑火（P2.2 的路由器已经为此卡过三轮）。
		if (properties.isMultiNamespace()) {
			log.info("[path-prefix] 多命名空间形态（单体）生效，共 {} 个：{}",
					properties.getNamespaces().size(),
					properties.getNamespaces().stream()
							.map(ns -> ns.getValue() + "→" + ns.getBasePackage())
							.toList());
		}
		else {
			log.info("[path-prefix] 生效 prefix={} basePackage={} legacyCompat={}",
					properties.getValue(), properties.getBasePackage(), properties.isLegacyCompat());
		}
		return new WebMvcConfigurer() {
			@Override
			public void configurePathMatch(PathMatchConfigurer configurer) {
				// 单体：逐个命名空间各挂各的前缀。
				// PathMatchConfigurer#addPathPrefix 可以调多次 —— 它把 (prefix, predicate)
				// 按插入序存进 LinkedHashMap，RequestMappingHandlerMapping#getPathPrefix
				// 取**第一个** predicate 命中的前缀。所以 basePackage 之间不能有包含关系，
				// 否则谁先注册谁赢 —— 门禁 PathPrefixNamespaceTest 守着这一条。
				if (properties.isMultiNamespace()) {
					for (PathNamespace ns : properties.getNamespaces()) {
						// basePackage 留空 = 该命名空间不由 addPathPrefix 加前缀
						// （im-server：/api 写死在 controller 源码里，再加一层会变成 /api/api/im/**）。
						// 它仍然参与安全链圈范围，只是不参与这里。
						if (!ns.addsMvcPrefix()) {
							continue;
						}
						String basePackage = ns.getBasePackage();
						configurer.addPathPrefix(ns.normalizedValue(),
								type -> type.getPackageName().startsWith(basePackage));
					}
					return;
				}
				String basePackage = properties.getBasePackage();
				configurer.addPathPrefix(properties.getValue(),
						type -> !basePackage.isBlank()
								&& type.getPackageName().startsWith(basePackage));
			}
		};
	}

	/**
	 * ⚠️ 多命名空间（单体）下**不注册**这个兼容过滤器。
	 *
	 * <p>它是按<b>单个</b> {@code value} 构造的，多命名空间下「老路径」根本没有唯一定义。
	 * 而且它的存在意义是「加前缀这一次发版的零窗口」—— 单体是全新部署形态，
	 * 没有「调用方还在发老路径」这个迁移期，本来就不需要它。
	 *
	 * <p>用条件表达式而不是只在配置里写 {@code legacy-compat: false}：
	 * 忘了写的话会注册一个前缀为空串的过滤器，它会把每个请求都拿去 handler 映射里试匹配 ——
	 * 那是**请求线程上的**开销与故障面（2026-08-15 auth 就是在这个过滤器里每请求 500 被回滚的）。
	 */
	@Bean
	@ConditionalOnProperty(prefix = "zhiflow.path-prefix", name = "legacy-compat",
			havingValue = "true", matchIfMissing = true)
	@ConditionalOnExpression(PathPrefixConstants.SINGLE_NAMESPACE_CONDITION)
	public FilterRegistrationBean<LegacyPathPrefixFilter> legacyPathPrefixFilter(
			PathPrefixProperties properties,
			// ⚠️ 必须按 bean 名限定：容器里有**两个** RequestMappingHandlerMapping ——
			// MVC 的 requestMappingHandlerMapping 与 actuator 的 controllerEndpointHandlerMapping
			// （后者是前者的子类）。不加 @Qualifier 时 ObjectProvider.getIfAvailable() 会抛
			// NoUniqueBeanDefinitionException，而它是在**请求线程**里抛的 ⇒ 每个请求 500。
			// 2026-08-15 实测：auth 发版时 /actuator/prometheus 连续 500，流水线健康检查 40 次不过后回滚。
			@Qualifier("requestMappingHandlerMapping")
			ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
		FilterRegistrationBean<LegacyPathPrefixFilter> registration = new FilterRegistrationBean<>(
				new LegacyPathPrefixFilter(properties.getValue(), handlerMapping));
		registration.setOrder(LegacyPathPrefixFilter.ORDER);
		registration.addUrlPatterns("/*");
		return registration;
	}
}
