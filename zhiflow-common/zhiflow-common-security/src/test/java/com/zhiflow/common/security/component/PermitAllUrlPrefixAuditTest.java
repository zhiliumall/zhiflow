package com.zhiflow.common.security.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermitAllUrlProperties#findUnprefixedLiterals} 的判据锁。
 *
 * <p>这条自检存在的理由见该方法的 javadoc：P2.3 打开内部路径前缀后，配置里
 * {@code release-urls} 的字面量是原样使用的，没带前缀的条目**一条都匹配不上**，
 * 而且完全静默。2026-08-17 实测各份配置里的业务白名单绝大多数都已失效。
 *
 * <p>用例里的输入全部取自当天的真实配置内容，不是编的。
 */
class PermitAllUrlPrefixAuditTest {

	@Test
	@DisplayName("mall-admin 真实配置：9 条业务路径全部判为失配，基础设施路径不误报")
	void mallAdminLiterals_businessOnesAreFlagged() {
		List<String> live = List.of(
				"/actuator/**", "/v3/api-docs/**", "/api/**",
				"/shopapply/addedit", "/shopinfo/count", "/shopapply/one",
				"/orderrefunds/notify-refunds/**",
				"/shopdeposit/notify-ali/**", "/shopdeposit/notify-wx/**",
				"/ordercashier/notify-wx/**", "/ordercashier/notify-ali/**",
				"/ordercashier/notify-global-wechat/**");

		List<String> flagged = PermitAllUrlProperties.findUnprefixedLiterals(live, "/mall");

		assertTrue(flagged.contains("/shopapply/addedit"));
		assertTrue(flagged.contains("/ordercashier/notify-global-wechat/**"));
		// 基础设施两条不该被点名：它们不是 controller，addPathPrefix 不改写它们
		assertTrue(flagged.stream().noneMatch(u -> u.startsWith("/actuator")));
		assertTrue(flagged.stream().noneMatch(u -> u.startsWith("/v3/api-docs")));
		// /api/** 在 mall-admin 下确实没有对应 controller（/api 是 im 的前缀），算失配
		assertEquals(10, flagged.size(), "9 条业务 + /api/** = 10 条");
	}

	@Test
	@DisplayName("upms-admin 真实配置：/druid 与 /actuator 不误报，其余业务路径全部点名")
	void upmsAdminLiterals_infraIsExempt() {
		List<String> live = List.of(
				"/actuator/**", "/v3/api-docs/**", "/druid/**",
				"/user/register", "/user/count", "/tenant/outside/**",
				"/tenantapply/addedit", "/tenantapply/one", "/tenant/count",
				"/file/upload/out", "/tenantpackagerecharge/notify-ali/**",
				"/tenantpackagerecharge/notify-wx/**", "/agreementconfig/type/**",
				"/phone/code", "/email/code/**", "/user/info/phone/**", "/user/info/thirdparty/**");

		List<String> flagged = PermitAllUrlProperties.findUnprefixedLiterals(live, "/upms");

		assertEquals(14, flagged.size(), "17 条里减去 actuator / api-docs / druid 三条基础设施");
		assertTrue(flagged.contains("/agreementconfig/type/**"), "这条有 3 个活的 C 端调用方，最该被点名");
	}

	@Test
	@DisplayName("已带前缀的条目不点名")
	void prefixedLiterals_areNotFlagged() {
		List<String> fixed = List.of("/mall/shopapply/addedit", "/mall/ordercashier/notify-wx/**", "/mall");
		assertTrue(PermitAllUrlProperties.findUnprefixedLiterals(fixed, "/mall").isEmpty());
	}

	@Test
	@DisplayName("前缀相近但不同的服务不会互相放过：/mallapi 的条目在 /mall 服务里仍算失配")
	void similarPrefix_isNotConfusedWithTheRealOne() {
		// startsWith("/mall") 会把 "/mallapi/x" 误判为已带前缀，故判据必须是 prefix + "/"
		List<String> literals = List.of("/mallapi/orderinfo/notify-wx/**");
		assertEquals(literals, PermitAllUrlProperties.findUnprefixedLiterals(literals, "/mall"));
	}

	@Test
	@DisplayName("没配前缀的服务（im-server）跳过自检，不刷无意义 WARN")
	void serviceWithoutPrefix_isSkipped() {
		List<String> literals = List.of("/api/im/**", "/whatever");
		assertTrue(PermitAllUrlProperties.findUnprefixedLiterals(literals, "").isEmpty());
		assertTrue(PermitAllUrlProperties.findUnprefixedLiterals(literals, null).isEmpty());
	}

	@Test
	@DisplayName("null 与非 / 开头的条目不参与判定，不抛异常")
	void malformedEntries_areIgnored() {
		List<String> literals = new java.util.ArrayList<>();
		literals.add(null);
		literals.add("relative/path");
		literals.add("/shopapply/one");
		assertEquals(List.of("/shopapply/one"), PermitAllUrlProperties.findUnprefixedLiterals(literals, "/mall"));
	}
}
