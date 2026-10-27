package com.zhiflow.common.core.util;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 业务服务（Servlet 侧）取用户真实 IP 的统一口径（压测后优化）。
 *
 * <h2>为什么是 X-Real-IP 而不是 X-Client-IP</h2>
 * <p>反向代理在转发前会<b>覆盖写</b> {@code X-Real-IP: $remote_addr}，所以这个头在到达服务前
 * 不可能被客户端伪造；而 {@code X-Client-IP} 若请求绕开反向代理直连服务，客户端自带的值会
 * 原样到达，不可信。只有在能确认请求确实经过自己的反向代理（对端是内网 / 回环）时，才采信
 * {@code X-Real-IP}。
 *
 * <h2>判定</h2>
 * <ol>
 *   <li>对端是回环 / 内网（10/8、172.16/12、192.168/16、127/8、::1、fc00::/7）且 {@code X-Real-IP} 恰好一个值、
 *       是合法 IP 字面量 ⇒ 用它（IPv4 映射的 IPv6 规整成点分，其余原样去空白）；</li>
 *   <li>否则 ⇒ 对端地址（IPv6 回环规整成 {@code 127.0.0.1}，与 {@link IpUtils#getIpAddr} 一致）。</li>
 * </ol>
 * 只认字面量，不做 DNS。
 *
 * <h2>开关</h2>
 * <p>{@value #ENABLED_KEY}，默认关。只有去空白后忽略大小写等于 {@code true} 才算开，
 * 缺失 / 写错 / 读取抛错一律按关。关时每个调用点都走调用方传入的原取法，行为与改前逐字节一致。
 */
public final class ClientIpResolver {

	public static final String ENABLED_KEY = "zhiflow.perf.client-ip.enabled";

	static final String X_REAL_IP = "X-Real-IP";

	private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

	private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]*:[0-9a-fA-F:.]*$");

	private static final List<String> TRUSTED_PEERS = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
			"127.0.0.0/8", "::1/128", "fc00::/7");

	private static final BooleanSupplier OFF = () -> false;

	private static volatile BooleanSupplier switchSupplier = OFF;

	private ClientIpResolver() {
	}

	/** 由 {@code ClientIpSwitchAutoConfiguration} 在启动时绑定到 Environment（每次现读）；单测可直接注入。 */
	public static void bindSwitch(BooleanSupplier supplier) {
		switchSupplier = supplier == null ? OFF : supplier;
	}

	public static boolean enabled() {
		try {
			return switchSupplier.getAsBoolean();
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	/** 开关值的宽容解析：只有 {@code true}（忽略大小写与空白）算开。 */
	public static boolean parseSwitch(String value) {
		return value != null && "true".equalsIgnoreCase(value.trim());
	}

	/**
	 * 开关开 ⇒ {@link #trusted(HttpServletRequest)}；开关关 ⇒ 调用方的原取法。
	 * @param legacy 改前的取法（如 {@code request::getRemoteAddr}），关时原样返回它的结果
	 */
	public static String resolve(HttpServletRequest request, Supplier<String> legacy) {
		return enabled() && request != null ? trusted(request) : legacy.get();
	}

	/**
	 * 给支付渠道的终端 IP（如微信下单接口的 {@code spbill_create_ip}）。
	 * <p>开关开且解析出 IPv4 ⇒ 用它；解析出 IPv6 或开关关 ⇒ 调用方的原取法。渠道对 IPv6 的支持没有逐个核实，
	 * 原取法（容器内网 IPv4）是一直在用、渠道一直接受的格式，往「下单不失败」方向兜底。
	 */
	public static String forPayment(HttpServletRequest request, Supplier<String> legacy) {
		if (!enabled() || request == null) {
			return legacy.get();
		}
		String ip = trusted(request);
		byte[] addr = literal(ip);
		return addr != null && addr.length == 4 ? ip : legacy.get();
	}

	/** 新口径本身，不看开关（见类注释）。 */
	public static String trusted(HttpServletRequest request) {
		String remote = request.getRemoteAddr();
		byte[] peer = literal(remote);
		if (peer != null && inAny(peer, TRUSTED_PEERS)) {
			Enumeration<String> values = request.getHeaders(X_REAL_IP);
			List<String> realIps = values == null ? List.of() : Collections.list(values);
			if (realIps.size() == 1) {
				String fromHeader = normalize(realIps.get(0));
				if (fromHeader != null) {
					return fromHeader;
				}
			}
		}
		if (peer != null && peer.length == 16 && isLoopback(peer)) {
			return "127.0.0.1";
		}
		String normalized = normalize(remote);
		return normalized != null ? normalized : remote;
	}

	/** 两个 IP 文本是否同一地址（按字节比，{@code ::1}/{@code 0:0:0:0:0:0:0:1}、v4 映射 v6 等写法差异不影响）。 */
	public static boolean sameAddress(String a, String b) {
		byte[] x = literal(a);
		byte[] y = literal(b);
		return x != null && y != null && java.util.Arrays.equals(x, y);
	}

	/** 合法 IP 字面量 ⇒ 规整后的文本；否则 null。IPv4 与 v4 映射 v6 ⇒ 点分；IPv6 ⇒ 原样去空白。 */
	static String normalize(String raw) {
		byte[] addr = literal(raw);
		if (addr == null) {
			return null;
		}
		if (addr.length == 4) {
			return (addr[0] & 0xFF) + "." + (addr[1] & 0xFF) + "." + (addr[2] & 0xFF) + "." + (addr[3] & 0xFF);
		}
		return raw.trim();
	}

	/** 只认 IP 字面量（不做 DNS）；IPv4 映射的 IPv6 返回 4 字节。与网关 {@code PayNotifyIpAllowlistFilter#literal} 同一口径。 */
	static byte[] literal(String s) {
		if (s == null) {
			return null;
		}
		String v = s.trim();
		if (!(IPV4.matcher(v).matches() || IPV6.matcher(v).matches())) {
			return null;
		}
		try {
			// ofLiteral 只解析字面量，非法（如 999.1.1.1）直接抛错，不会像 getByName 那样退化成 DNS 查询
			return InetAddress.ofLiteral(v).getAddress();
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static boolean isLoopback(byte[] v6) {
		for (int i = 0; i < 15; i++) {
			if (v6[i] != 0) {
				return false;
			}
		}
		return v6[15] == 1;
	}

	private static boolean inAny(byte[] addr, List<String> cidrs) {
		for (String cidr : cidrs) {
			int slash = cidr.indexOf('/');
			byte[] net = literal(cidr.substring(0, slash));
			int bits = Integer.parseInt(cidr.substring(slash + 1));
			if (net == null || net.length != addr.length) {
				continue;
			}
			boolean match = true;
			for (int i = 0; i < net.length && bits > 0; i++, bits -= 8) {
				int mask = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
				if ((net[i] & mask) != (addr[i] & mask)) {
					match = false;
					break;
				}
			}
			if (match) {
				return true;
			}
		}
		return false;
	}

}
