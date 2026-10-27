package com.zhiflow.mall.common.observability;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 指标标签的基数纪律。
 *
 * <p>标签只取有界集合：租户号只收合法纯数字，每进程最多 {@value #MAX_TENANTS} 个不同值，超出记 {@code other}；
 * 渠道 / 类别这类「代码里的词」只收 {@code [a-z0-9_]}，每类最多 {@value #MAX_TOKENS_PER_KIND} 个值。
 * 用户 id、订单号、traceId 永远不进标签——它们只进日志与 trace。
 *
 * <p>租户号来自请求路径 / 上下文，回调端点上它是公网任意可填的，不设上限就是一个把 Prometheus 撑爆的口子。
 */
public final class ObservabilityTags {

	public static final String OTHER = "other";

	static final int MAX_TENANTS = 30;

	static final int MAX_TOKENS_PER_KIND = 30;

	private static final Pattern TENANT_ID = Pattern.compile("^-?\\d{1,20}$");

	private static final Pattern TOKEN = Pattern.compile("^[a-z0-9_]{1,40}$");

	private static final Set<String> TENANTS = ConcurrentHashMap.newKeySet();

	private static final Map<String, Set<String>> TOKENS = new ConcurrentHashMap<>();

	private ObservabilityTags() {
	}

	/** 租户标签：非法值与超出上限的新值一律 {@code other}。 */
	public static String tenant(String raw) {
		if (raw == null) {
			return OTHER;
		}
		String t = raw.trim();
		if (!TENANT_ID.matcher(t).matches()) {
			return OTHER;
		}
		return admit(TENANTS, t, MAX_TENANTS);
	}

	/**
	 * 代码词标签（渠道、类别、依赖操作名）。统一转小写、{@code -} 与空格换成 {@code _}；
	 * 不合规或超出该类上限的新值记 {@code other}。
	 *
	 * @param kind 标签类别（如 {@code channel}），各类别分别计上限
	 */
	public static String token(String kind, String raw) {
		if (raw == null || raw.isBlank()) {
			return OTHER;
		}
		String t = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
		if (!TOKEN.matcher(t).matches()) {
			return OTHER;
		}
		return admit(TOKENS.computeIfAbsent(kind, k -> ConcurrentHashMap.newKeySet()), t, MAX_TOKENS_PER_KIND);
	}

	private static String admit(Set<String> seen, String value, int cap) {
		if (seen.contains(value)) {
			return value;
		}
		// 并发下可能多放进一两个，量级有界即可，不值得为此加锁
		if (seen.size() >= cap) {
			return OTHER;
		}
		seen.add(value);
		return value;
	}

	/** 仅供测试：清空已登记的标签值。 */
	static void resetForTest() {
		TENANTS.clear();
		TOKENS.clear();
	}
}
