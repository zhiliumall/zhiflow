package com.zhiflow.auth.handler;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthEventMetricsTest {

	@Test
	@DisplayName("grant_type → kind：刷新单独计；没有 grant_type 的失败（客户端认证 / 探针内省）不算登录失败")
	void kinds() {
		assertThat(AuthEventMetrics.successKind("password")).isEqualTo("login_success");
		assertThat(AuthEventMetrics.successKind("refresh_token")).isEqualTo("token_refresh_success");
		assertThat(AuthEventMetrics.failureKind("sms")).isEqualTo("login_failure");
		assertThat(AuthEventMetrics.failureKind("refresh_token")).isEqualTo("token_refresh_failure");
		assertThat(AuthEventMetrics.failureKind(null)).isEqualTo("other_failure");
		assertThat(AuthEventMetrics.failureKind(" ")).isEqualTo("other_failure");
	}

	@Test
	@DisplayName("挂在全局注册表上：Boot 加进来的 registry 能看到全部 kind（预注册为 0）与计数")
	void countsOnGlobalRegistry() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
		try {
			AuthEventMetrics.record(AuthEventMetrics.LOGIN_FAILURE);
			AuthEventMetrics.record("not-a-kind");

			assertThat(registry.get(AuthEventMetrics.NAME).tag("kind", "login_failure").counter().count()).isEqualTo(1);
			assertThat(registry.get(AuthEventMetrics.NAME).tag("kind", "token_refresh_failure").counter().count())
				.isZero();
			assertThat(registry.find(AuthEventMetrics.NAME).tag("kind", "not-a-kind").counter()).isNull();
		}
		finally {
			Metrics.removeRegistry(registry);
		}
	}

}
