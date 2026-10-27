package com.zhiflow.common.core.constant;

import java.util.List;

public final class PublicUrlConstants {

	private PublicUrlConstants() {
	}

	/**
	 * 所有资源服务器共享的**基础设施级**公开路径（Swagger、静态资源）。
	 * 不包含业务路径，以避免与其他服务的同名 admin 接口发生误命中。
	 *
	 * <p>⚠️ [P2.3] 这些路径**不属于任何服务的 controller**，
	 * {@code addPathPrefix} 按包名断言不会改写它们 ⇒ 它们<b>永远不加前缀</b>。
	 * 给它们加前缀反而会让它们失配。
	 *
	 * <p>⚠️ {@code /captcha/**} 已于 2026-08-15 从本列表移到
	 * {@link #PORTAL_PUBLIC_URLS}：{@code CaptchaController} 在
	 * {@code com.zhiflow.mall.api.controller} 包里，是**会被加前缀的业务 controller**，
	 * 而且全仓只有 mall-api 有 captcha 端点（按各服务 {@code /actuator/mappings} 实测），
	 * 放在共享列表里既不准确、加前缀时也没法区别对待。
	 */
	public static final List<String> COMMON_PUBLIC_URLS = List.of(
			"/static/**",
			"/v3/api-docs/**",
			"/swagger-ui/**"
	);

	/**
	 * H5 门户（zhiflow-mall-api）的公开业务路径。
	 *
	 * <p>⚠️ [P2.3] 这些**全部是 mall-api 自己的 controller**，所以打开 {@code zhiflow.path-prefix.value}
	 * 之后它们的真实路径会多一段前缀，本列表必须跟着走 ——
	 * 用 {@link PathPrefixConstants#prefixed(String, List)}，不要直接用裸列表。
	 *
	 * <p>本列表同时是 {@code ThirdSessionInterceptor} 的 {@code excludePathPatterns}
	 * （common-portal 的 {@code WebConfig}），失配的后果是拦截器开始在这些路径上运行：
	 * 带 {@code client-type: H5/H5_PC/APP} 且不带 {@code tenant-id} 头的请求被判 60004 拒绝
	 * （<b>四端登录要用的 {@code /captcha/**} 正属此类</b>）；支付回调不带
	 * {@code client-type} 头、走的是不拒绝的分支，但会被塞进默认租户上下文。
	 * 无论哪一种，<b>权限矩阵都探不到</b>（这些路径全是 POST / 含 notify 动词）。
	 */
	public static final List<String> PORTAL_PUBLIC_URLS = List.of(
			"/captcha/**",
			"/wxuser/loginmp",
			"/wxapp/*"
	);
}
