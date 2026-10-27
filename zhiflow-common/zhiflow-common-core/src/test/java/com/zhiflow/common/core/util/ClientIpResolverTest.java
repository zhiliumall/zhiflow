package com.zhiflow.common.core.util;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.dto.ClientLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务侧用户真实 IP 统一口径（CARD-06）。
 *
 * <p>夹具按两条真实链路造：经网关时对端是网桥 172.18.0.1、经 openresty 直连服务（{@code /im/ws}、{@code /portal/}）
 * 时对端同样是 172.18.0.1，两者都由 openresty 覆盖写 X-Real-IP；伪造者能控制的是 XFF 左段、X-Client-* 头，
 * 以及「直连服务时自带的 X-Real-IP」——后者只在对端不可信时才可能生效。
 */
class ClientIpResolverTest {

	private static final String DOCKER_GW = "172.18.0.1";

	private static final String REAL = "223.74.1.1";

	@AfterEach
	void off() {
		ClientIpResolver.bindSwitch(null);
	}

	private static void on() {
		ClientIpResolver.bindSwitch(() -> true);
	}

	private static MockHttpServletRequest from(String peer) {
		MockHttpServletRequest r = new MockHttpServletRequest();
		r.setRemoteAddr(peer);
		return r;
	}

	/** openresty 经网关进来的一次正常请求，外加客户端能伪造的全部东西。 */
	private static MockHttpServletRequest spoofedViaEdge() {
		MockHttpServletRequest r = from(DOCKER_GW);
		r.addHeader("X-Real-IP", REAL);
		r.addHeader("X-Forwarded-For", "8.8.8.8, " + REAL + ", " + DOCKER_GW);
		r.addHeader(CommonConstants.CLIENT_IP, "9.9.9.9");
		return r;
	}

	@Test
	@DisplayName("可信对端：取 openresty 覆盖写的 X-Real-IP；XFF 左段与客户端自带的 X-Client-IP 都不影响结果")
	void trustedPeerUsesRealIp() {
		assertEquals(REAL, ClientIpResolver.trusted(spoofedViaEdge()));
		for (String peer : List.of("127.0.0.1", "10.1.2.3", "192.168.1.9", "172.31.255.1", "::1", "fd00::5")) {
			MockHttpServletRequest r = from(peer);
			r.addHeader("X-Real-IP", REAL);
			assertEquals(REAL, ClientIpResolver.trusted(r), "peer=" + peer);
		}
	}

	@Test
	@DisplayName("不可信（公网）对端带来的 X-Real-IP 不采信，用对端地址（对照：可信对端同样的头 ⇒ 采信）")
	void untrustedPeerHeaderIgnored() {
		MockHttpServletRequest r = from("203.0.113.7");
		r.addHeader("X-Real-IP", "1.2.3.4");
		assertEquals("203.0.113.7", ClientIpResolver.trusted(r));
		MockHttpServletRequest control = from(DOCKER_GW);
		control.addHeader("X-Real-IP", "1.2.3.4");
		assertEquals("1.2.3.4", ClientIpResolver.trusted(control));
	}

	@Test
	@DisplayName("X-Real-IP 非法 / 多值 / 带逗号 ⇒ 不采信，用对端地址；没有 X-Real-IP ⇒ 对端地址（不去 XFF 里找）")
	void invalidRealIpFallsBackToPeer() {
		for (String bad : List.of("evil", "1.2.3.4, 5.6.7.8", "999.1.1.1", "", "example.com", "unknown")) {
			MockHttpServletRequest r = from(DOCKER_GW);
			r.addHeader("X-Real-IP", bad);
			r.addHeader("X-Forwarded-For", "8.8.8.8");
			assertEquals(DOCKER_GW, ClientIpResolver.trusted(r), "X-Real-IP=" + bad);
		}
		MockHttpServletRequest two = from(DOCKER_GW);
		two.addHeader("X-Real-IP", "1.1.1.1");
		two.addHeader("X-Real-IP", "2.2.2.2");
		assertEquals(DOCKER_GW, ClientIpResolver.trusted(two));
		MockHttpServletRequest none = from(DOCKER_GW);
		none.addHeader("X-Forwarded-For", "8.8.8.8");
		assertEquals(DOCKER_GW, ClientIpResolver.trusted(none));
	}

	@Test
	@DisplayName("IPv6：原样（去空白）；IPv4 映射 IPv6 ⇒ 点分；对端 IPv6 回环 ⇒ 127.0.0.1")
	void ipv6Forms() {
		MockHttpServletRequest v6 = from(DOCKER_GW);
		v6.addHeader("X-Real-IP", " 2408:8456:1a2b:3c4d::1 ");
		assertEquals("2408:8456:1a2b:3c4d::1", ClientIpResolver.trusted(v6));
		MockHttpServletRequest mapped = from(DOCKER_GW);
		mapped.addHeader("X-Real-IP", "::ffff:203.0.113.9");
		assertEquals("203.0.113.9", ClientIpResolver.trusted(mapped));
		assertEquals("127.0.0.1", ClientIpResolver.trusted(from("0:0:0:0:0:0:0:1")));
	}

	@Test
	@DisplayName("开关关 ⇒ 原取法原样返回（逐调用点不变）；开 ⇒ 新口径")
	void resolveHonoursSwitch() {
		MockHttpServletRequest r = spoofedViaEdge();
		assertEquals(DOCKER_GW, ClientIpResolver.resolve(r, r::getRemoteAddr));
		on();
		assertEquals(REAL, ClientIpResolver.resolve(r, r::getRemoteAddr));
	}

	@Test
	@DisplayName("支付终端 IP：开且解析出 IPv4 ⇒ 真实 IP；解析出 IPv6 ⇒ 原取法（渠道对 IPv6 未逐个核实）；关 ⇒ 原取法")
	void paymentIp() {
		MockHttpServletRequest r = spoofedViaEdge();
		assertEquals(DOCKER_GW, ClientIpResolver.forPayment(r, r::getRemoteAddr));
		on();
		assertEquals(REAL, ClientIpResolver.forPayment(r, r::getRemoteAddr));
		MockHttpServletRequest v6 = from(DOCKER_GW);
		v6.addHeader("X-Real-IP", "2408:8456:1a2b:3c4d::1");
		assertEquals(DOCKER_GW, ClientIpResolver.forPayment(v6, v6::getRemoteAddr));
	}

	@Test
	@DisplayName("开关宽容解析：只有 true（忽略大小写与空白）算开；读取抛错按关")
	void switchParsing() {
		assertTrue(ClientIpResolver.parseSwitch("true"));
		assertTrue(ClientIpResolver.parseSwitch(" TRUE "));
		for (String bad : new String[] { null, "", "ture", "1", "yes", "on", "false" }) {
			assertFalse(ClientIpResolver.parseSwitch(bad), "值=" + bad);
		}
		ClientIpResolver.bindSwitch(() -> {
			throw new IllegalStateException("配置中心不可用");
		});
		assertFalse(ClientIpResolver.enabled());
		MockHttpServletRequest r = spoofedViaEdge();
		assertEquals(DOCKER_GW, ClientIpResolver.resolve(r, r::getRemoteAddr));
	}

	@Test
	@DisplayName("sameAddress 按字节比：写法不同的同一地址相等，非法输入不等")
	void sameAddress() {
		assertTrue(ClientIpResolver.sameAddress("::1", "0:0:0:0:0:0:0:1"));
		assertTrue(ClientIpResolver.sameAddress("::ffff:1.2.3.4", "1.2.3.4"));
		assertFalse(ClientIpResolver.sameAddress("1.2.3.4", "1.2.3.5"));
		assertFalse(ClientIpResolver.sameAddress(null, "1.2.3.4"));
		assertFalse(ClientIpResolver.sameAddress("evil", "evil"));
	}

	// ───────── 两个静态消费方：ClientInfoUtils、IpUtils ─────────

	@Test
	@DisplayName("ClientInfoUtils 开：IP 走新口径；直连路径上客户端自带的位置头（X-Client-IP 对不上）整组不采信")
	void clientInfoUtilsDropsForgedLocation() {
		MockHttpServletRequest direct = from(DOCKER_GW);
		direct.addHeader("X-Real-IP", REAL);
		direct.addHeader(CommonConstants.CLIENT_IP, "8.8.8.8");
		direct.addHeader(CommonConstants.CLIENT_PROVINCE, "California");
		direct.addHeader(CommonConstants.CLIENT_LNG, "-122.0");
		// 对照：关（现状）时伪造的 IP 与位置原样落下
		ClientLocation before = ClientInfoUtils.getClientLocation(direct);
		assertEquals("8.8.8.8", before.getIp());
		assertEquals("California", before.getProvince());
		on();
		ClientLocation after = ClientInfoUtils.getClientLocation(direct);
		assertEquals(REAL, after.getIp());
		assertNull(after.getProvince());
		assertNull(after.getLongitude());
	}

	@Test
	@DisplayName("ClientInfoUtils 开：经网关（X-Client-IP 与新口径同一地址）时位置头照常采信")
	void clientInfoUtilsKeepsGatewayLocation() {
		on();
		MockHttpServletRequest viaGateway = from(DOCKER_GW);
		viaGateway.addHeader("X-Real-IP", REAL);
		viaGateway.addHeader(CommonConstants.CLIENT_IP, REAL);
		viaGateway.addHeader(CommonConstants.CLIENT_PROVINCE, "%E5%B9%BF%E4%B8%9C%E7%9C%81");
		ClientLocation loc = ClientInfoUtils.getClientLocation(viaGateway);
		assertEquals(REAL, loc.getIp());
		assertEquals("广东省", loc.getProvince());
	}

	@Test
	@DisplayName("IpUtils.getIpAddr 开：不再取整串 XFF（对照：关时客户端带一个 XFF 就能伪造）")
	void ipUtilsUsesTrustedWhenOn() {
		MockHttpServletRequest r = spoofedViaEdge();
		assertEquals("8.8.8.8, " + REAL + ", " + DOCKER_GW, IpUtils.getIpAddr(r));
		on();
		assertEquals(REAL, IpUtils.getIpAddr(r));
		assertEquals("unknown", IpUtils.getIpAddr(null));
	}

}
