package com.zhiflow.common.core.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * {@code app_info{app,version,git_sha,form} 1}：每个进程一条，发版观察窗与看板靠它区分新旧版本。
 *
 * <h2>⚠️ 注册名必须是 {@code app.info}（点号），不能写 {@code app_info}</h2>
 * <p>Micrometer 的 Prometheus 注册表只对名字以 {@code .info} 结尾的 gauge 走 Info 类型、导出成 {@code app_info}；
 * 写成下划线的 {@code app_info} 会被当普通 gauge，而 Prometheus 客户端把 {@code _info} 当保留后缀剥掉，
 * 最终导出成一个叫 {@code app} 的指标 —— 不报错，只是查不到。{@code AppInfoMetricsTest} 用真实的
 * Prometheus 注册表钉住导出名。
 *
 * <p>取值来源：version = {@code META-INF/build-info.properties} 的 {@code build.version} →
 * {@code git.properties} 的 {@code git.build.version} → 清单 {@code Implementation-Version} → {@code unknown}；
 * git_sha = {@code git.commit.id.abbrev} → {@code unknown}（根 pom 的 git-commit-id 插件每个模块都生成）。
 */
public class AppInfoMetrics implements MeterBinder {

	public static final String NAME = "app.info";

	/** zhiflow-boot 的装配 profile（见 boot/assembly/*Assembly）。 */
	private static final List<String> BOOT_ASSEMBLIES = List.of("all");

	private final String app;

	private final String version;

	private final String gitSha;

	private final String form;

	public AppInfoMetrics(Environment environment) {
		this(environment, AppInfoMetrics.class.getClassLoader());
	}

	AppInfoMetrics(Environment environment, ClassLoader classLoader) {
		this.app = environment.getProperty("spring.application.name", "unknown");
		Properties git = load(classLoader, "git.properties");
		Properties build = load(classLoader, "META-INF/build-info.properties");
		this.version = firstNonBlank(build.getProperty("build.version"), git.getProperty("git.build.version"),
				AppInfoMetrics.class.getPackage().getImplementationVersion(), "unknown");
		this.gitSha = firstNonBlank(git.getProperty("git.commit.id.abbrev"), "unknown");
		this.form = formOf(this.app, environment.getActiveProfiles());
	}

	static String formOf(String app, String[] activeProfiles) {
		if (!"zhiflow-boot".equals(app)) {
			return "micro";
		}
		return Arrays.stream(activeProfiles).filter(BOOT_ASSEMBLIES::contains).findFirst().orElse("boot");
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		Gauge.builder(NAME, () -> 1)
			.description("进程版本信息，值恒为 1")
			.tag("app", app)
			.tag("version", version)
			.tag("git_sha", gitSha)
			.tag("form", form)
			.strongReference(true)
			.register(registry);
	}

	String version() {
		return version;
	}

	String gitSha() {
		return gitSha;
	}

	String form() {
		return form;
	}

	private static Properties load(ClassLoader classLoader, String resource) {
		Properties properties = new Properties();
		try (InputStream in = classLoader.getResourceAsStream(resource)) {
			if (in != null) {
				properties.load(in);
			}
		}
		catch (IOException | RuntimeException e) {
			// 读不到就当没有，落到 unknown
		}
		return properties;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value.trim();
			}
		}
		return "unknown";
	}

}
