package com.zhiflow.common.security.config;

import com.zhiflow.common.security.component.PermitAllUrlProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.List;

/**
 * PermitAllUrlProperties 配置类
 * 解决 Spring Boot 3.4.x @ConfigurationProperties 自动注册导致的 Bean 重复问题
 */
@Slf4j
@Configuration
@ConditionalOnExpression("!'${security.oauth2.client.release-urls}'.isEmpty()")
public class PermitAllUrlPropertiesConfiguration {

	private static final String RELEASE_URLS_KEY = "security.oauth2.client.release-urls";

	/** 判断某个 property source 有没有定义这份列表：看它有没有第 0 个元素 */
	private static final String FIRST_ELEMENT_KEY = RELEASE_URLS_KEY + "[0]";

	@Bean
	public PermitAllUrlProperties permitAllUrlProperties(Environment environment) throws Exception {
		PermitAllUrlProperties properties = new PermitAllUrlProperties();

		Binder binder = Binder.get(environment);
		List<String> releaseUrls = binder.bind(RELEASE_URLS_KEY,
				List.class)
				.orElse(new ArrayList<>());

		warnOnShadowedDefinitions(environment, releaseUrls);

		properties.setReleaseUrls(new ArrayList<>(releaseUrls));
		properties.afterPropertiesSet();

		return properties;
	}

	/**
	 * 启动自检：{@code release-urls} 在多个配置源里各定义了一份时打 WARN，并点名哪一份生效。
	 *
	 * <p><b>为什么必须有这条</b>（2026-08-17 实测）：当时各服务靠 {@code spring.config.import}
	 * 叠加多份外部配置——服务自己的一份 release-urls，加上全局共享的一份。
	 * 而 Spring 的 {@code spring.config.import} 是<b>后导入的优先级更高</b>，
	 * 且 {@link Binder} 绑 {@code List} 时按最高优先级来源<b>整体取、不做合并</b>
	 * —— 于是共享那份把每个服务自己那份**整份盖掉**，
	 * 各服务配的白名单<b>一条都没生效过</b>，而且完全静默：进程正常起、日志一行不报。
	 *
	 * <p>决定性判据：{@code /dify/**} 只写在共享配置里，在 zhiflow-weixin 容器上直连
	 * {@code /dify/x} 返回 404（已放行）；只写在服务自己那份配置里的
	 * {@code /weixin/open/auth/**} 返回 424（未放行）—— 微信开放平台授权链路因此断了。
	 *
	 * <p>WARN 而不是 fail-fast：多处定义本身不是错误（共享一份 + 各服务补充是常见写法），
	 * 错的是「以为服务那份生效」。把生效来源与被盖掉的来源都打出来，看日志就能立刻判断。
	 */
	private void warnOnShadowedDefinitions(Environment environment, List<String> effective) {
		if (!(environment instanceof ConfigurableEnvironment configurable)) {
			return;
		}
		List<String> definingSources = new ArrayList<>();
		for (PropertySource<?> source : configurable.getPropertySources()) {
			if (source instanceof EnumerablePropertySource<?> enumerable
					&& enumerable.containsProperty(FIRST_ELEMENT_KEY)) {
				definingSources.add(source.getName());
			}
		}
		if (definingSources.size() <= 1) {
			return;
		}
		log.warn("""
				[permit-all] ⚠ {} 在 {} 个配置源里各定义了一份，Binder 绑 List 只取最高优先级那一份、**不合并**。
				生效来源：{}
				被整份盖掉：{}
				实际生效的白名单（{} 条）：{}
				⇒ 要放行的路径必须加在「生效来源」那一份里，且带上本服务的内部路径前缀；
				  加在被盖掉的那份里不会有任何效果，也不会有任何报错。""",
				RELEASE_URLS_KEY, definingSources.size(),
				definingSources.getFirst(), definingSources.subList(1, definingSources.size()),
				effective.size(), effective);
	}
}
