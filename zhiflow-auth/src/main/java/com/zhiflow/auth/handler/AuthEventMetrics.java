package com.zhiflow.auth.handler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

import java.util.HashMap;
import java.util.Map;

/**
 * 登录认证事件计数 {@code auth_events_total{kind}}（可观测性方案第十八章：登录失败率是安全信号）。
 *
 * <p>kind：
 * <ul>
 *   <li>{@code login_success} / {@code login_failure}：令牌端点上除刷新之外的所有授权方式（密码、短信、第三方…）；</li>
 *   <li>{@code token_refresh_success} / {@code token_refresh_failure}：{@code grant_type=refresh_token}，
 *       单独计，免得前端静默续期把登录成功数灌大；</li>
 *   <li>{@code other_failure}：请求里没有 {@code grant_type} 的失败 —— 客户端认证失败、令牌内省
 *       （监控探针每 30 秒打一次 check_token 就走这里），不是用户登录，单独计免得把登录失败率抬高；</li>
 *   <li>{@code captcha_failure}：验证码在<b>网关</b>校验，由网关的同名指标计（按 job 区分），这里不计。</li>
 * </ul>
 *
 * <p>挂全局注册表：两个 handler 是在安全链装配时 {@code new} 出来的（构造器签名由 Lombok 生成、多一个字段就会变），
 * 拿不到 bean；Boot 默认把它的 registry 加进全局组合注册表。类加载时各 kind 预注册为 0。
 */
final class AuthEventMetrics {

	static final String NAME = "auth_events_total";

	static final String LOGIN_SUCCESS = "login_success";

	static final String LOGIN_FAILURE = "login_failure";

	static final String TOKEN_REFRESH_SUCCESS = "token_refresh_success";

	static final String TOKEN_REFRESH_FAILURE = "token_refresh_failure";

	static final String OTHER_FAILURE = "other_failure";

	private static final Map<String, Counter> COUNTERS = register(LOGIN_SUCCESS, LOGIN_FAILURE,
			TOKEN_REFRESH_SUCCESS, TOKEN_REFRESH_FAILURE, OTHER_FAILURE);

	private AuthEventMetrics() {
	}

	static String successKind(String grantType) {
		return OAuth2ParameterNames.REFRESH_TOKEN.equals(grantType) ? TOKEN_REFRESH_SUCCESS : LOGIN_SUCCESS;
	}

	/** @param grantType 请求里原样的 grant_type，可为 null */
	static String failureKind(String grantType) {
		if (grantType == null || grantType.isBlank()) {
			return OTHER_FAILURE;
		}
		return OAuth2ParameterNames.REFRESH_TOKEN.equals(grantType) ? TOKEN_REFRESH_FAILURE : LOGIN_FAILURE;
	}

	private static Map<String, Counter> register(String... kinds) {
		Map<String, Counter> counters = new HashMap<>();
		for (String kind : kinds) {
			counters.put(kind, Counter.builder(NAME).description("登录认证事件").tag("kind", kind)
				.register(Metrics.globalRegistry));
		}
		return Map.copyOf(counters);
	}

	/** 观测旁路，任何异常都吞掉，绝不影响令牌端点的响应。 */
	static void record(String kind) {
		try {
			Counter counter = COUNTERS.get(kind);
			if (counter != null) {
				counter.increment();
			}
		}
		catch (RuntimeException e) {
			// 观测代码不打断业务
		}
	}

}
