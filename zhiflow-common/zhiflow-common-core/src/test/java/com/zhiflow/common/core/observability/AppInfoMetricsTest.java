package com.zhiflow.common.core.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class AppInfoMetricsTest {

	@Test
	@DisplayName("导出名是 app_info（Info 类型）且带 app/version/git_sha/form 四个标签，值为 1")
	void exportedAsAppInfo() {
		PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		new AppInfoMetrics(new MockEnvironment().withProperty("spring.application.name", "zhiflow-mall-api"))
			.bindTo(registry);

		String scrape = registry.scrape();

		assertThat(scrape).containsPattern(
				"(?m)^app_info\\{app=\"zhiflow-mall-api\",form=\"micro\",git_sha=\"[^\"]+\",version=\"[^\"]+\"} 1(\\.0)?$");
	}

	@Test
	@DisplayName("对照：同样的 gauge 用下划线名 app_info 注册，会被导出成 app —— 所以注册名必须是 app.info")
	void underscoreNameWouldBeRenamed() {
		PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		Gauge.builder("app_info", () -> 1).tag("app", "x").register(registry);

		assertThat(registry.scrape()).doesNotContain("app_info{").containsPattern("(?m)^app\\{app=\"x\"}");
	}

	@Test
	@DisplayName("form：普通服务 micro；zhiflow-boot 取装配 profile，认不出是 boot")
	void form() {
		assertThat(AppInfoMetrics.formOf("zhiflow-gateway", new String[] { "all" })).isEqualTo("micro");
		assertThat(AppInfoMetrics.formOf("zhiflow-boot", new String[] { "dev", "all" })).isEqualTo("all");
		assertThat(AppInfoMetrics.formOf("zhiflow-boot", new String[0])).isEqualTo("boot");
	}

	@Test
	@DisplayName("读不到 git.properties / build-info 时落 unknown，不抛")
	void unknownWhenNoResources() {
		ClassLoader empty = new ClassLoader(null) {
		};
		AppInfoMetrics metrics = new AppInfoMetrics(new MockEnvironment(), empty);
		assertThat(metrics.gitSha()).isEqualTo("unknown");
		assertThat(metrics.form()).isEqualTo("micro");
	}

	@Test
	@DisplayName("自动配置登记在 AutoConfiguration.imports 里（各服务扫描面不含本包，漏登记 = 运行期没有这个指标）")
	void registeredInImports() {
		assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
			.contains(ObservabilityAutoConfiguration.class.getName());
	}

}
