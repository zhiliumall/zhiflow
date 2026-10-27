package com.zhiflow.boot.scope;

import com.zhiflow.common.core.constant.PathNamespace;
import com.zhiflow.common.core.constant.PathPrefixConstants;
import com.zhiflow.common.core.jackson.HashidsScope;
import com.zhiflow.common.data.web.PathPrefixProperties;
import jakarta.servlet.DispatcherType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.EnumSet;
import java.util.List;

/**
 * [P3] {@code hashids.salt} 的作用范围还原（单体专用）。
 *
 * <h2>⭐ 多进程零变化</h2>
 * <p>整个配置类挂 {@link PathPrefixConstants#MULTI_NAMESPACE_CONDITION}，与
 * {@code GatewayParityConfiguration} 同一套机制：多进程形态从不配
 * {@code zhiflow.path-prefix.namespaces}，这里一个 bean 都不注册，
 * {@link HashidsScope#setDefaultEnabled} 也没人调 ⇒ 进程级默认保持 {@code true} =
 * 现状。**「零变化」由「新代码根本不执行」保证。**
 *
 * <h2>🔴 认不出 mall-api 命名空间时：什么都不做</h2>
 * <p>如果拿不到 mall-api 的前缀，就<b>不改默认、不注册 filter</b>，只打 ERROR。
 * 理由是两种失败的代价不对称：
 * <ul>
 * <li>「不改」的代价 = 后台商品页继续是坏的（已知、可见、有报错）；</li>
 * <li>「改了但认不出 C 端请求」的代价 = 整个 C 端的 id 契约当场翻转 ——
 *     H5 已在用 hashid 形态请求商详，翻转后是全站商详 404。</li>
 * </ul>
 * ⇒ 漏配时退到<b>不动</b>那一边，与 {@code PathNamespace#authenticationMode}
 * 「漏配退到更严的一边」是同一条纪律。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnExpression(PathPrefixConstants.MULTI_NAMESPACE_CONDITION)
public class HashidsScopeConfiguration {

	/** 在 gateway-parity 四件之后（它们只关心 /oauth2/token，与本 filter 无交集）。 */
	public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

	/** mall-api 的 controller 包前缀——用它在 namespaces 里认领属于 C 端的那一条。 */
	static final String PORTAL_BASE_PACKAGE = "com.zhiflow.mall.api";

	@Bean
	public FilterRegistrationBean<HashidsScopeFilter> hashidsScopeFilter(PathPrefixProperties properties) {
		String portalPrefix = resolvePortalPrefix(properties.getNamespaces());
		if (portalPrefix == null) {
			log.error("[hashids-scope] 未能在 zhiflow.path-prefix.namespaces 里认领 mall-api "
					+ "（base-package 以 {} 开头的条目），**不改动 hashids 作用范围**。"
					+ "后果：后台返回的商品 id 仍是 hashid 形态、按它回查会「商品不存在」。", PORTAL_BASE_PACKAGE);
			FilterRegistrationBean<HashidsScopeFilter> disabled = new FilterRegistrationBean<>();
			disabled.setEnabled(false);
			return disabled;
		}

		// 进程级默认置关 = 对齐「除 mall-api 外其余服务都没有 salt」。
		// 非请求线程（定时任务 / MQ / CDC sink）落在这个默认上。
		HashidsScope.setDefaultEnabled(false);
		log.info("[hashids-scope] hashids 编码收敛到 mall-api 命名空间 {}，进程级默认=关"
				+ "（多进程形态下 salt 本就只有 mall-api 进程有）", portalPrefix);

		FilterRegistrationBean<HashidsScopeFilter> registration = new FilterRegistrationBean<>(
				new HashidsScopeFilter(portalPrefix));
		registration.setOrder(ORDER);
		registration.addUrlPatterns("/*");
		// ASYNC：MVC 异步返回类型在 ASYNC 派发上写响应体，那是另一个线程——
		// 不注册 ASYNC 的话它拿不到 ThreadLocal，会落到进程级默认(关)，C 端异步接口会吐原始 id。
		// ERROR：错误页也可能带实体。
		registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
		return registration;
	}

	/** 取 base-package 以 mall-api 包开头、且带前缀的那一条。找不到返回 null（由调用方决定怎么退）。 */
	static String resolvePortalPrefix(List<PathNamespace> namespaces) {
		if (namespaces == null) {
			return null;
		}
		return namespaces.stream()
			.filter(PathNamespace::hasValue)
			.filter(ns -> ns.getBasePackage() != null && ns.getBasePackage().startsWith(PORTAL_BASE_PACKAGE))
			.map(PathNamespace::normalizedValue)
			.findFirst()
			.orElse(null);
	}

}
