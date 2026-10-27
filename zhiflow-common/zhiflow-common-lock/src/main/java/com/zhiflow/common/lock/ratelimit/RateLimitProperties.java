package com.zhiflow.common.lock.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code zhiflow.rate-limit.*}：总开关与按规则名覆盖注解上的阈值。
 */
@Data
@ConfigurationProperties(prefix = "zhiflow.rate-limit")
public class RateLimitProperties {

	/** 总开关，默认开。 */
	private boolean enabled = true;

	/** 规则名 → 阈值；没配的规则用注解上的默认值。 */
	private Map<String, Rule> rules = new LinkedHashMap<>();

	@Data
	public static class Rule {

		private Long permits;

		private Long periodSeconds;
	}
}
