package com.zhiflow.mall.api.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * 取渠道能力：实现类声明的默认值 + 配置项 {@code pay.channel.<code>.cap.<字段>} 覆盖。
 *
 * <p>每次调用现读 {@link Environment}，不缓存，配置变更即时生效。
 * 覆盖值写错（布尔 / 时长 / 整数解析失败）按默认值走并打 WARN，不让一个配置错误把支付链路打断。
 *
 * <p>可覆盖字段：close、expire、query、
 * max-pay-window（ISO-8601 时长，如 {@code P7D}、{@code PT24H}）、query-qps。
 */
@Slf4j
@Component
public class ChannelCapabilityResolver {

	static final String PREFIX = "pay.channel.";

	private final PaymentChannelRegistry registry;

	private final Environment env;

	public ChannelCapabilityResolver(PaymentChannelRegistry registry, Environment env) {
		this.registry = registry;
		this.env = env;
	}

	/** 渠道未注册时按最保守的口径返回（不关单），而不是抛：能力查询不能成为入账链路的故障点。 */
	public ChannelCapabilities of(String channelCode) {
		ChannelCapabilities base = registry.find(channelCode).map(PaymentChannel::capabilities)
				.orElse(ChannelCapabilities.CONSERVATIVE);
		String p = PREFIX + channelCode + ".cap.";
		return new ChannelCapabilities(
				bool(p + "close", base.close()),
				bool(p + "expire", base.expire()),
				bool(p + "query", base.query()),
				duration(p + "max-pay-window", base.maxPayWindow()),
				integer(p + "query-qps", base.queryQps()));
	}

	/** 所有已注册渠道里最长的可付窗口（补单任务 SQL 粗筛用，逐单再按各自渠道的窗口细筛）。 */
	public Duration longestPayWindow() {
		return registry.registeredCodes().stream().map(c -> of(c).maxPayWindow()).max(Duration::compareTo)
				.orElse(ChannelCapabilities.CONSERVATIVE.maxPayWindow());
	}

	private boolean bool(String key, boolean def) {
		String v = env.getProperty(key);
		if (v == null || v.isBlank()) {
			return def;
		}
		if ("true".equalsIgnoreCase(v.trim()) || "false".equalsIgnoreCase(v.trim())) {
			return Boolean.parseBoolean(v.trim());
		}
		log.warn("渠道能力配置 {}={} 不是布尔值，按默认 {}", key, v, def);
		return def;
	}

	private int integer(String key, int def) {
		String v = env.getProperty(key);
		if (v == null || v.isBlank()) {
			return def;
		}
		try {
			int n = Integer.parseInt(v.trim());
			return n > 0 ? n : def;
		}
		catch (NumberFormatException e) {
			log.warn("渠道能力配置 {}={} 不是整数，按默认 {}", key, v, def);
			return def;
		}
	}

	private Duration duration(String key, Duration def) {
		String v = env.getProperty(key);
		if (v == null || v.isBlank()) {
			return def;
		}
		try {
			Duration d = Duration.parse(v.trim());
			return d.isNegative() ? def : d;
		}
		catch (DateTimeParseException e) {
			log.warn("渠道能力配置 {}={} 不是 ISO-8601 时长，按默认 {}", key, v, def);
			return def;
		}
	}

}
