package com.zhiflow.boot.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 恶意路径拦截（网关 {@code SecurityGlobalFilter} 的移植）。
 *
 * <h2>反向验证</h2>
 * <ul>
 * <li>把 {@code BLOCKED_PATHS} 里的 {@code /actuator/heapdump} 删掉 →
 *     {@code sensitiveActuatorEndpointsAreBlocked} 变红（这一条在 boot 里是
 *     permitAll /actuator/** 之下唯一的闸）；</li>
 * <li>把 {@code stripFirstSegment} 改成恒等返回 → {@code prefixedScannerPathsAreBlocked} 变红
 *     （网关是 strip 后再匹配，少了这层就与原网关行为分叉）。</li>
 * </ul>
 */
class BlockedPathsFilterTest {

	private final BlockedPathsFilter filter = new BlockedPathsFilter();

	@Test
	@DisplayName("裸的扫描器路径 403（/.env /.git /wp-admin ...）")
	void bareScannerPathsAreBlocked() throws Exception {
		for (String path : new String[] { "/.env", "/.git/config", "/wp-admin", "/phpinfo.php" }) {
			MockFilterChain chain = new MockFilterChain();
			MockHttpServletResponse response = new MockHttpServletResponse();
			this.filter.doFilter(get(path), response, chain);
			assertEquals(403, response.getStatus(), path + " 没被拦下");
			assertNull(chain.getRequest(), path + " 已放行到下游");
		}
	}

	@Test
	@DisplayName("带命名空间前缀的扫描器路径同样 403（对应网关 strip 后匹配的语义）")
	void prefixedScannerPathsAreBlocked() throws Exception {
		for (String path : new String[] { "/upms/.env", "/mall/.git/config", "/mallapi/wp-login.php",
				"/upms/actuator/env" }) {
			MockFilterChain chain = new MockFilterChain();
			MockHttpServletResponse response = new MockHttpServletResponse();
			this.filter.doFilter(get(path), response, chain);
			assertEquals(403, response.getStatus(), path + " 没被拦下（网关形态下它是 403）");
			assertNull(chain.getRequest(), path + " 已放行到下游");
		}
	}

	@Test
	@DisplayName("⭐ /actuator/env|heapdump|threaddump 被拦：release-urls 的 permitAll /actuator/** 之下唯一的闸")
	void sensitiveActuatorEndpointsAreBlocked() throws Exception {
		for (String path : new String[] { "/actuator/env", "/actuator/heapdump", "/actuator/threaddump" }) {
			MockFilterChain chain = new MockFilterChain();
			MockHttpServletResponse response = new MockHttpServletResponse();
			this.filter.doFilter(get(path), response, chain);
			assertEquals(403, response.getStatus(), path + " 没被拦下 —— permitAll + exposure 配宽即泄漏");
			assertNull(chain.getRequest());
		}
	}

	@Test
	@DisplayName("匹配大小写不敏感（网关是 path.toLowerCase() 后匹配）")
	void matchingIsCaseInsensitive() throws Exception {
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.filter.doFilter(get("/UPMS/.ENV"), response, chain);
		assertEquals(403, response.getStatus());
		assertNull(chain.getRequest());
	}

	@Test
	@DisplayName("CONNECT 隧道尝试 405")
	void connectMethodIsRejected() throws Exception {
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockHttpServletRequest request = new MockHttpServletRequest("CONNECT", "/anything");
		request.setRequestURI("/anything");
		this.filter.doFilter(request, response, chain);
		assertEquals(405, response.getStatus());
		assertNull(chain.getRequest());
	}

	@Test
	@DisplayName("正常业务路径直通（含 /actuator/health —— 黑名单只拦三个敏感 actuator 端点）")
	void normalPathsPassThrough() throws Exception {
		for (String path : new String[] { "/mallapi/goodsspu/page", "/upms/user/page", "/oauth2/token",
				"/actuator/health", "/mall/ai/health/profile" }) {
			MockFilterChain chain = new MockFilterChain();
			MockHttpServletResponse response = new MockHttpServletResponse();
			this.filter.doFilter(get(path), response, chain);
			assertNotNull(chain.getRequest(), path + " 被误拦了");
			assertEquals(200, response.getStatus());
		}
	}

	@Test
	@DisplayName("stripFirstSegment 判据自检")
	void stripFirstSegmentSemantics() {
		assertEquals("/.env", BlockedPathsFilter.stripFirstSegment("/upms/.env"));
		assertEquals("/", BlockedPathsFilter.stripFirstSegment("/upms"));
		assertEquals("/", BlockedPathsFilter.stripFirstSegment("/"));
		assertEquals("/actuator/env", BlockedPathsFilter.stripFirstSegment("/upms/actuator/env"));
	}

	private static MockHttpServletRequest get(String path) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.setRequestURI(path);
		return request;
	}

}
