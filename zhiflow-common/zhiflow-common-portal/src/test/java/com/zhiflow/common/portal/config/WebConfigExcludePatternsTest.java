package com.zhiflow.common.portal.config;

import com.zhiflow.common.core.constant.PublicUrlConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [P2.3] {@code ThirdSessionInterceptor} 的排除清单必须跟着内部路径前缀走。
 *
 * <h2>为什么这几行值得单独测</h2>
 * <p>这张清单里躺着 {@code /orderinfo/notify-order-*}、{@code /vipuserrecharge/notify-*}、
 * {@code /logistics/callback/*} 这些**支付与物流回调**，以及四端登录都要用的
 * {@code /captcha/**}。打开前缀后 controller 的真实路径变成 {@code /mallapi/...}，
 * 清单没跟上就意味着 {@code ThirdSessionInterceptor} 开始在这些路径上运行：
 * 带 {@code client-type: H5/H5_PC/APP} 且不带 {@code tenant-id} 头的请求被判 60004 拒绝
 * （<b>四端登录的 captcha 正属此类</b>），回调则会被塞进默认租户上下文。
 *
 * <p>而这类故障<b>权限矩阵一条都探不到</b>：回调全是 POST / 含 {@code notify} 动词，
 * captcha 也是 POST，工具按设计不发这些请求。所以判据只能落在这里。
 */
class WebConfigExcludePatternsTest {

	@Test
	@DisplayName("没配前缀时逐字等于「门户业务路径 + 基础设施路径」")
	void withoutPrefixListIsUnchanged() {
		List<String> excluded = WebConfig.excludedPatterns("");
		assertEquals(PublicUrlConstants.PORTAL_PUBLIC_URLS.size() + PublicUrlConstants.COMMON_PUBLIC_URLS.size(),
				excluded.size());
		assertTrue(excluded.containsAll(PublicUrlConstants.PORTAL_PUBLIC_URLS));
		assertTrue(excluded.containsAll(PublicUrlConstants.COMMON_PUBLIC_URLS));
	}

	@Test
	@DisplayName("配了前缀后：业务路径带前缀，基础设施路径原样")
	void withPrefixOnlyBusinessPathsAreRewritten() {
		List<String> excluded = WebConfig.excludedPatterns("/mallapi");

		assertTrue(excluded.contains("/mallapi/wxuser/loginmp"), excluded.toString());
		// 四端登录的滑块验证码：CaptchaController 在 mall-api 的 controller 包里，会被加前缀
		assertTrue(excluded.contains("/mallapi/captcha/**"));

		// 基础设施：不是本服务 controller，addPathPrefix 不改写它们，加前缀反而失配
		assertTrue(excluded.contains("/static/**"));
		assertTrue(excluded.contains("/swagger-ui/**"));
		assertTrue(excluded.contains("/v3/api-docs/**"));
		assertTrue(excluded.stream().noneMatch(p -> p.startsWith("/mallapi/static")
				|| p.startsWith("/mallapi/swagger-ui") || p.startsWith("/mallapi/v3/api-docs")),
				"基础设施路径被加了前缀：" + excluded);
	}

	@Test
	@DisplayName("清单条数不随前缀变化 —— 只有形态变，不许有条目凭空消失")
	void prefixDoesNotDropEntries() {
		assertEquals(WebConfig.excludedPatterns("").size(), WebConfig.excludedPatterns("/mallapi").size());
	}
}
