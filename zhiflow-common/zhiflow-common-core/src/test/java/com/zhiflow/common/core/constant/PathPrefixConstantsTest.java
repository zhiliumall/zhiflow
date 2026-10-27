package com.zhiflow.common.core.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [P2.3] 写死的路径字面量跟着内部路径前缀走。
 *
 * <p>这几条断言守的是两个**权限矩阵探不到**的故障：支付回调被要求登录态
 * （{@code PORTAL_PUBLIC_URLS} 同时是 {@code ThirdSessionInterceptor} 的排除清单，
 * 而回调全是 POST），以及 mall-admin「未识别查询参数一律 400」那道闸静默失效
 * （查询串层面的事，矩阵只记状态码与业务码）。所以判据只能落在这里。
 */
class PathPrefixConstantsTest {

	@Test
	@DisplayName("没配前缀时是恒等变换 —— 合代码与开前缀因此可以分两次发")
	void blankPrefixIsIdentity() {
		List<String> urls = List.of("/orderinfo/notify-order-wx/*", "/captcha/**");
		assertEquals(urls, PathPrefixConstants.prefixed("", urls));
		assertEquals(urls, PathPrefixConstants.prefixed(null, urls));
		assertEquals(urls, PathPrefixConstants.prefixed("   ", urls));
	}

	@Test
	@DisplayName("加前缀后是「前缀 + 原路径」，通配符原样保留")
	void prefixIsPrepended() {
		assertEquals(List.of("/mallapi/orderinfo/notify-order-wx/*", "/mallapi/captcha/**"),
				PathPrefixConstants.prefixed("/mallapi",
						List.of("/orderinfo/notify-order-wx/*", "/captcha/**")));
	}

	@Test
	@DisplayName("前缀写法归一：mallapi / /mallapi / /mallapi/ 结果相同")
	void prefixIsNormalized() {
		List<String> urls = List.of("/liveconfig");
		assertEquals(List.of("/mallapi/liveconfig"), PathPrefixConstants.prefixed("mallapi", urls));
		assertEquals(List.of("/mallapi/liveconfig"), PathPrefixConstants.prefixed("/mallapi/", urls));
		assertEquals(List.of("/mallapi/liveconfig"), PathPrefixConstants.prefixed(" /mallapi ", urls));
	}

	@Test
	@DisplayName("⚠️ PORTAL_PUBLIC_URLS 里不许混进「不是本服务 controller」的路径")
	void portalListContainsOnlyPrefixableBusinessPaths() {
		// addPathPrefix 按包名断言只改写本服务 controller，所以 actuator / swagger / 静态资源
		// 这些路径**不会**被加前缀。它们一旦混进这张表，加前缀那一刻就会失配：
		// ThirdSessionInterceptor 会开始拦截 swagger 与静态资源，而白名单里那条永远匹配不上。
		// 2026-08-15 已把 /captcha/** 之外的三条基础设施路径移回 COMMON_PUBLIC_URLS。
		for (String url : PublicUrlConstants.PORTAL_PUBLIC_URLS) {
			for (String infra : new String[] { "/static", "/swagger-ui", "/v3/api-docs", "/actuator", "/error",
					"/webjars" }) {
				assertFalse(url.startsWith(infra),
						"PORTAL_PUBLIC_URLS 里混进了基础设施路径 " + url + "，它不会被 addPathPrefix 改写");
			}
		}
	}

	@Test
	@DisplayName("⚠️ 登录用的 captcha 必须在 PORTAL 表里 —— 掉出去 H5 登录、注册全挂")
	void portalListStillCoversCaptcha() {
		// 支付回调（/paynotify/**）不在表里也照常可达：PaymentNotifyController 不挂 @ApiLogin，
		// 拦截器走「不需要登录」分支放行，租户由回调地址里的 {tenantId} 设定
		assertTrue(PublicUrlConstants.PORTAL_PUBLIC_URLS.contains("/captcha/**"),
				"/captcha/** 不在 PORTAL_PUBLIC_URLS 里：不带 tenant-id 头的 H5 请求取验证码会被判 60004 拒绝");
	}

	@Test
	@DisplayName("COMMON_PUBLIC_URLS 只放不会被加前缀的基础设施路径")
	void commonListIsInfrastructureOnly() {
		assertEquals(List.of("/static/**", "/v3/api-docs/**", "/swagger-ui/**"),
				PublicUrlConstants.COMMON_PUBLIC_URLS,
				"COMMON 是所有服务共享且永不加前缀的那一批；业务路径请放 PORTAL_PUBLIC_URLS");
	}

	@Test
	@DisplayName("属性名与占位符是编译期常量（要能直接写进 @Value 注解）")
	void placeholderIsCompileTimeConstant() {
		assertEquals("zhiflow.path-prefix.value", PathPrefixConstants.PROPERTY);
		assertEquals("${zhiflow.path-prefix.value:}", PathPrefixConstants.PLACEHOLDER);
	}
}
