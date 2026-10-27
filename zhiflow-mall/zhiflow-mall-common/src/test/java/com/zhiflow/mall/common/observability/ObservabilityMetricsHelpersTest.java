package com.zhiflow.mall.common.observability;

import com.zhiflow.common.core.exception.BizException;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标签基数闸、交易 / 任务指标的命名与预注册。
 */
class ObservabilityMetricsHelpersTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	@BeforeEach
	void setUp() {
		ObservabilityTags.resetForTest();
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void tearDown() {
		Metrics.removeRegistry(registry);
		registry.clear();
		ObservabilityTags.resetForTest();
	}

	@Test
	void tenantTag_onlyNumeric_andCappedPerProcess() {
		assertThat(ObservabilityTags.tenant("1")).isEqualTo("1");
		assertThat(ObservabilityTags.tenant("-1")).isEqualTo("-1");
		assertThat(ObservabilityTags.tenant(" 1024 ")).isEqualTo("1024");
		assertThat(ObservabilityTags.tenant("1; DROP SCHEMA public")).isEqualTo(ObservabilityTags.OTHER);
		assertThat(ObservabilityTags.tenant(null)).isEqualTo(ObservabilityTags.OTHER);
		assertThat(ObservabilityTags.tenant("")).isEqualTo(ObservabilityTags.OTHER);
		IntStream.range(100, 100 + ObservabilityTags.MAX_TENANTS).forEach(i -> ObservabilityTags.tenant(String.valueOf(i)));
		// 上限已满：新值记 other，已登记的值照常
		assertThat(ObservabilityTags.tenant("999999")).isEqualTo(ObservabilityTags.OTHER);
		assertThat(ObservabilityTags.tenant("1")).isEqualTo("1");
	}

	@Test
	void tokenTag_normalizesAndBoundsPerKind() {
		assertThat(ObservabilityTags.token("channel", "AliPay")).isEqualTo("alipay");
		assertThat(ObservabilityTags.token("channel", "wx-pay")).isEqualTo("wx_pay");
		assertThat(ObservabilityTags.token("channel", "../etc")).isEqualTo(ObservabilityTags.OTHER);
		assertThat(ObservabilityTags.token("channel", null)).isEqualTo(ObservabilityTags.OTHER);
		IntStream.range(0, ObservabilityTags.MAX_TOKENS_PER_KIND).forEach(i -> ObservabilityTags.token("category", "c" + i));
		assertThat(ObservabilityTags.token("category", "overflow")).isEqualTo(ObservabilityTags.OTHER);
		// 各类别分开计上限
		assertThat(ObservabilityTags.token("channel", "wxpay")).isEqualTo("wxpay");
	}

	@Test
	void orderCreate_countsByOutcome_andPreRegistersTenants() {
		BizMetrics.preRegister(List.of("1", "2"));
		assertThat(registry.find(BizMetrics.M_ORDER_CREATE).counters()).hasSize(6);

		BizMetrics.orderCreate("1", BizMetrics.orderOutcomeOf(null));
		BizMetrics.orderCreate("1", BizMetrics.orderOutcomeOf(new BizException("库存不足")));
		BizMetrics.orderCreate("1", BizMetrics.orderOutcomeOf(new IllegalStateException("db")));
		assertThat(registry.get(BizMetrics.M_ORDER_CREATE).tags("tenant", "1", "outcome", "success").counter().count())
				.isEqualTo(1.0);
		assertThat(registry.get(BizMetrics.M_ORDER_CREATE).tags("tenant", "1", "outcome", "biz_reject").counter().count())
				.isEqualTo(1.0);
		assertThat(registry.get(BizMetrics.M_ORDER_CREATE).tags("tenant", "1", "outcome", "error").counter().count())
				.isEqualTo(1.0);
	}

	@Test
	void payCallback_countsAndTimes_withBoundedChannel() {
		BizMetrics.payCallback("1", "WxPay", BizMetrics.CALLBACK_OK, 1_000_000L);
		BizMetrics.payCallback("x' OR 1=1", "alipay", BizMetrics.CALLBACK_REJECTED, 2_000_000L);
		assertThat(registry.get(BizMetrics.M_PAY_CALLBACK).tags("tenant", "1", "channel", "wxpay", "outcome", "ok")
				.counter().count()).isEqualTo(1.0);
		assertThat(registry.get(BizMetrics.M_PAY_CALLBACK).tags("tenant", "other", "channel", "alipay").counter().count())
				.isEqualTo(1.0);
		assertThat(registry.get(BizMetrics.M_PAY_CALLBACK_LATENCY).tag("channel", "wxpay").timer().count()).isEqualTo(1);
	}

	@Test
	void paymentTypeMapping() {
		assertThat(BizMetrics.channelOfPaymentType("1")).isEqualTo("wxpay");
		assertThat(BizMetrics.channelOfPaymentType("2")).isEqualTo("alipay");
		assertThat(BizMetrics.channelOfPaymentType("9")).isEqualTo("9");
		assertThat(BizMetrics.channelOfPaymentType(null)).isEqualTo(ObservabilityTags.OTHER);
	}

	@Test
	void jobMetrics_preRegisterAllOutcomes_andRecord() {
		JobMetrics.preRegister("PromoLedgerDailySnapshotTask#runYesterday");
		assertThat(registry.find(JobMetrics.M_RUNS).tag("job", "PromoLedgerDailySnapshotTask#runYesterday").counters())
				.hasSize(4);
		JobMetrics.record("PromoLedgerDailySnapshotTask#runYesterday", JobMetrics.FAILURE, 5_000_000L);
		assertThat(registry.get(JobMetrics.M_RUNS).tags("job", "PromoLedgerDailySnapshotTask#runYesterday",
				"outcome", "failure").counter().count()).isEqualTo(1.0);
		assertThat(registry.get(JobMetrics.M_DURATION).tag("job", "PromoLedgerDailySnapshotTask#runYesterday").timer()
				.count()).isEqualTo(1);
	}

	/** 「整轮跳过」标记：取一次即清；只属于当前线程。 */
	@Test
	void jobMetrics_skipMark_consumedOnce_andThreadLocal() throws Exception {
		JobMetrics.consumeSkipped();
		assertThat(JobMetrics.consumeSkipped()).isFalse();

		JobMetrics.markSkipped();
		boolean[] seenByOtherThread = {true};
		Thread other = new Thread(() -> seenByOtherThread[0] = JobMetrics.consumeSkipped());
		other.start();
		other.join();
		assertThat(seenByOtherThread[0]).as("别的线程看不到本线程的标记").isFalse();

		assertThat(JobMetrics.consumeSkipped()).isTrue();
		assertThat(JobMetrics.consumeSkipped()).as("取一次即清").isFalse();
	}

	@org.junit.jupiter.api.Test
	void callbackChannelOnlyKeepsRegisteredCodes() {
		java.util.Set<String> registered = java.util.Set.of("4", "8");
		org.junit.jupiter.api.Assertions.assertEquals("8", BizMetrics.callbackChannel("8", registered::contains));
		// 公网乱填的渠道段不进标签（防刷序列、防拉低成功率判据）
		org.junit.jupiter.api.Assertions.assertEquals(BizMetrics.CHANNEL_UNKNOWN,
				BizMetrics.callbackChannel("x9-attack", registered::contains));
		org.junit.jupiter.api.Assertions.assertEquals(BizMetrics.CHANNEL_UNKNOWN, BizMetrics.callbackChannel(null, registered::contains));
		org.junit.jupiter.api.Assertions.assertEquals(BizMetrics.CHANNEL_UNKNOWN, BizMetrics.callbackChannel("8", c -> {
			throw new IllegalStateException("registry down");
		}));
	}
}
