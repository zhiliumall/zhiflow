package com.zhiflow.boot.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.observation.SecurityObservationSettings;

import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("Spring Security 观测关闭")
class SecurityObservationConfigurationTest {

	@Test
	@DisplayName("请求、认证、鉴权三类观测全部关闭")
	void allSecurityObservationsOff() {
		SecurityObservationSettings settings = new SecurityObservationConfiguration().securityObservationSettings();
		assertFalse(settings.shouldObserveRequests());
		assertFalse(settings.shouldObserveAuthentications());
		assertFalse(settings.shouldObserveAuthorizations());
	}

}
