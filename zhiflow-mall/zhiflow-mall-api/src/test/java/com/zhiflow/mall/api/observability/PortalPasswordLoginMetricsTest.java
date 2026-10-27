package com.zhiflow.mall.api.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zhiflow.common.core.util.R;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PortalPasswordLoginMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setup() {
        Metrics.globalRegistry.getMeters().stream()
                .filter(m -> PortalPasswordLoginMetrics.METRIC.equals(m.getId().getName()))
                .toList().forEach(Metrics.globalRegistry::remove);
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void cleanup() {
        Metrics.removeRegistry(registry);
        registry.close();
        MDC.clear();
    }

    @Test
    void initializesAllFiveZeroSeriesWithOnlyBoundedTags() {
        PortalPasswordLoginMetrics.initialize();
        PortalPasswordLoginMetrics.initialize();
        var counters = registry.find(PortalPasswordLoginMetrics.METRIC).counters();
        assertEquals(5, counters.size());
        for (var counter : counters) {
            assertEquals(0, counter.count());
            assertEquals(Set.of("channel", "outcome"), counter.getId().getTags().stream()
                    .map(t -> t.getKey()).collect(Collectors.toSet()));
            assertEquals("phone", counter.getId().getTag("channel"));
            assertTrue(Set.of("success", "password_mismatch", "account_not_found", "captcha_required",
                    "invalid_input").contains(counter.getId().getTag("outcome")));
        }
    }

    @Test
    void preservesExactResponseIdentityAndCountsOnce() {
        Map<String, Object> data = Map.of("captchaRequired", true, "private", "synthetic-private-value");
        R<Map<String, Object>> response = R.failed(data, 60010, "synthetic-sensitive-message");
        response.setErrCode("unchanged-error");
        assertSame(response, PortalPasswordLoginMetrics.observe("phone", "password_mismatch", response));
        assertSame(data, response.getData());
        assertEquals(60010, response.getCode());
        assertEquals("synthetic-sensitive-message", response.getMsg());
        assertEquals("unchanged-error", response.getErrCode());
        assertEquals(1, registry.get(PortalPasswordLoginMetrics.METRIC)
                .tags("channel", "phone", "outcome", "password_mismatch").counter().count());
    }

    @Test
    void prometheusExportsExactMetricAndZeroCombinationsWhenRegistryIsAttachedLater() {
        PortalPasswordLoginMetrics.initialize();
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            Metrics.addRegistry(prometheus);
            try {
                PortalPasswordLoginMetrics.observe("phone", "password_mismatch", R.failed(60010, "private"));
                var samples = prometheus.scrape().lines()
                        .filter(line -> line.startsWith("portal_password_login_total{")).toList();
                assertEquals(5, samples.size());
                assertTrue(samples.contains("portal_password_login_total{channel=\"phone\",outcome=\"password_mismatch\"} 1.0"));
                assertTrue(samples.contains("portal_password_login_total{channel=\"phone\",outcome=\"captcha_required\"} 0.0"));
                assertTrue(samples.stream().allMatch(line -> line.matches(
                        "portal_password_login_total\\{channel=\"phone\",outcome=\"[a-z_]+\"} [01]\\.0")));
            } finally {
                Metrics.removeRegistry(prometheus);
            }
        } finally {
            prometheus.close();
        }
    }

    @Test
    void logsOnlyFixedResultFieldsAndRetainsExistingTraceContext() {
        Logger logger = (Logger) LoggerFactory.getLogger(PortalPasswordLoginMetrics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            MDC.put("traceId", "synthetic-trace");
            R<?> response = R.failed(Map.of("password", "synthetic-password"), 60007, "synthetic-account");
            PortalPasswordLoginMetrics.observe("phone", "account_not_found", response);
            assertEquals(1, appender.list.size());
            assertEquals("PORTAL_PASSWORD_LOGIN channel=phone outcome=account_not_found code=60007",
                    appender.list.getFirst().getFormattedMessage());
            assertEquals(Map.of("traceId", "synthetic-trace"), appender.list.getFirst().getMDCPropertyMap());
            assertNull(appender.list.getFirst().getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void unknownOrNullLabelsCreateNoSeriesAndReturnOriginalResponse() {
        R<?> response = R.ok();
        for (String channel : List.of("synthetic-account", "", "phone")) {
            assertSame(response, PortalPasswordLoginMetrics.observe(channel, "synthetic-message", response));
        }
        assertSame(response, PortalPasswordLoginMetrics.observe("synthetic-account", "success", response));
        assertSame(response, PortalPasswordLoginMetrics.observe(null, "success", response));
        assertSame(response, PortalPasswordLoginMetrics.observe("phone", null, response));
        // 只看本指标：全局组合注册表里其它测试留下的指标会被挂到新加入的 registry 上，与本用例无关
        assertTrue(registry.find(PortalPasswordLoginMetrics.METRIC).meters().isEmpty());
    }

    @Test
    void registrationFailureCannotBreakInitializationOrResponse() {
        R<?> response = R.failed(60010, "unchanged");
        try (MockedStatic<Metrics> metrics = mockStatic(Metrics.class)) {
            metrics.when(() -> Metrics.counter(eq(PortalPasswordLoginMetrics.METRIC), any(String[].class)))
                    .thenThrow(new IllegalStateException("synthetic-registry-failure"));
            assertDoesNotThrow(PortalPasswordLoginMetrics::initialize);
            metrics.verify(() -> Metrics.counter(eq(PortalPasswordLoginMetrics.METRIC), any(String[].class)), times(5));
            assertSame(response, PortalPasswordLoginMetrics.observe("phone", "password_mismatch", response));
        }
    }

    @Test
    void resultLogEvaluationFailureCannotChangeResponseOrUndoCount() {
        R<?> response = mock(R.class);
        when(response.getCode()).thenThrow(new IllegalStateException("synthetic-log-evaluation-failure"));
        assertSame(response, PortalPasswordLoginMetrics.observe("phone", "success", response));
        assertEquals(1, registry.get(PortalPasswordLoginMetrics.METRIC)
                .tags("channel", "phone", "outcome", "success").counter().count());
        verify(response).getCode();
        verifyNoMoreInteractions(response);
    }
}
