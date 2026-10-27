package com.zhiflow.mall.api.observability;

import com.zhiflow.common.core.util.R;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Password-login outcomes only; observation must never change a business response. */
public final class PortalPasswordLoginMetrics {

    public static final String METRIC = "portal_password_login_total";
    private static final List<String> CHANNELS = List.of("phone");
    private static final List<String> OUTCOMES = List.of("success", "password_mismatch", "account_not_found",
            "captcha_required", "invalid_input");
    private static final Logger LOG = LoggerFactory.getLogger(PortalPasswordLoginMetrics.class);

    private PortalPasswordLoginMetrics() {
    }

    /** Register all fixed combinations before the first request, including zero outcomes. */
    public static void initialize() {
        for (String channel : CHANNELS) {
            for (String outcome : OUTCOMES) {
                try {
                    Metrics.counter(METRIC, "channel", channel, "outcome", outcome);
                } catch (RuntimeException ignored) {
                    // A broken registry must not prevent controller initialization.
                }
            }
        }
    }

    /** Return the original object, keeping data, message and HTTP handling untouched. */
    public static <T> R<T> observe(String channel, String outcome, R<T> result) {
        if (channel == null || outcome == null || !CHANNELS.contains(channel) || !OUTCOMES.contains(outcome)) {
            return result;
        }
        try {
            Metrics.counter(METRIC, "channel", channel, "outcome", outcome).increment();
        } catch (RuntimeException ignored) {
            // Counting is a side channel; retain the fixed result log if counting fails.
        }
        try {
            LOG.info("PORTAL_PASSWORD_LOGIN channel={} outcome={} code={}", channel, outcome, result.getCode());
        } catch (RuntimeException ignored) {
            // Neither the result body nor the observation failure is logged.
        }
        return result;
    }
}
