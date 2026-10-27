package com.zhiflow.boot.edge;

import cloud.tianai.captcha.cache.CacheStore;
import cloud.tianai.captcha.common.AnyMap;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关移植 filter 的**注册条件与顺序**门禁。
 *
 * <h2>反向验证（怎么证明这些用例不是假绿）</h2>
 * <ul>
 * <li>把 {@link GatewayParityConfiguration} 上的 {@code @ConditionalOnExpression} 删掉
 *     （或把 MULTI_NAMESPACE_CONDITION 换成字面 true）→
 *     {@code withoutNamespacesNothingRegisters} 变红；</li>
 * <li>把任意一个 registration 的 order 改到 -100（Security 链）之后 →
 *     {@code ordersSitBeforeSecurityAndTenantFilters} 变红；</li>
 * <li>把 enabled 逃生舱的默认值改成 false → {@code withNamespacesAllThreeRegister} 变红。</li>
 * </ul>
 */
class GatewayParityRegistrationTest {

	/** 与 TenantFilterConfiguration 里的取值一致：租户过滤器 = MIN_VALUE + 50。 */
	private static final int TENANT_FILTER_ORDER = Integer.MIN_VALUE + 50;

	/** SecurityProperties.DEFAULT_FILTER_ORDER：springSecurityFilterChain 的注册顺序。 */
	private static final int SECURITY_FILTER_CHAIN_ORDER = -100;

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
		.withUserConfiguration(StubDependencies.class, GatewayParityConfiguration.class);

	@Test
	@DisplayName("⭐ 多进程零变化：没配 namespaces 时，三个 filter 一个都不注册")
	void withoutNamespacesNothingRegisters() {
		// 多进程形态（每个服务一个进程）从不配置 zhiflow.path-prefix.namespaces。
		// 「网关还在、boot 的这些 filter 一个都不许注册」的机器判据就是这一条 ——
		// 与 BaseResourceServerConfiguration 的单体链同一套条件、同一套证法。
		this.runner.run(context -> {
			assertNull(context.getStartupFailure(), "上下文没起来");
			assertTrue(context.getBeansOfType(FilterRegistrationBean.class).isEmpty(),
					"没配 namespaces 却注册了网关移植 filter —— 多进程形态被单体改动污染了");
		});
	}

	@Test
	@DisplayName("单体（多命名空间）：三个 filter 全部注册")
	void withNamespacesAllThreeRegister() {
		this.runner.withPropertyValues("zhiflow.path-prefix.namespaces[0].value=/mallapi").run(context -> {
			assertNull(context.getStartupFailure(), "上下文没起来");
			assertEquals(3, context.getBeansOfType(FilterRegistrationBean.class).size(),
					"单体形态三件承重逻辑必须一件不少：from 剥离/恶意路径/验证码");
		});
	}

	@Test
	@DisplayName("顺序：from 剥离最早，且三个都在租户过滤器与 Security 链之前")
	void ordersSitBeforeSecurityAndTenantFilters() {
		this.runner.withPropertyValues("zhiflow.path-prefix.namespaces[0].value=/mallapi").run(context -> {
			int from = order(context.getBean("fromHeaderStrippingFilter", FilterRegistrationBean.class));
			int blocked = order(context.getBean("blockedPathsFilter", FilterRegistrationBean.class));
			int captcha = order(context.getBean("validateCodeServletFilter", FilterRegistrationBean.class));

			assertEquals(Ordered.HIGHEST_PRECEDENCE, from,
					"from 剥离必须最早执行 —— 晚于任何读头方就存在伪造窗口");
			assertTrue(from < blocked && blocked < captcha,
					"相对顺序必须与网关一致：剥离 → 恶意路径 → 验证码");
			assertTrue(from < TENANT_FILTER_ORDER,
					"from 剥离必须先于 TenantContextHolderFilter(MIN_VALUE+50)");
			assertTrue(captcha < SECURITY_FILTER_CHAIN_ORDER,
					"验证码必须先于 springSecurityFilterChain(-100)，否则授权服务器先发了令牌");
		});
	}

	@Test
	@DisplayName("逃生舱：gateway-parity.enabled=false 时一个都不注册（形态二/带网关过渡用）")
	void escapeHatchDisablesEverything() {
		// 这批 filter 与前置网关互斥（网关已核销滑块令牌，boot 再做一遍登录必断；
		// from 剥离还会砍掉拆分进程之间契约调用的 from: Y）。形态二 data-id 必须显式置 false。
		this.runner
			.withPropertyValues("zhiflow.path-prefix.namespaces[0].value=/mallapi",
					"zhiflow.monolith.gateway-parity.enabled=false")
			.run(context -> {
				assertNull(context.getStartupFailure(), "上下文没起来");
				assertTrue(context.getBeansOfType(FilterRegistrationBean.class).isEmpty(),
						"逃生舱没关掉 filter —— 形态二会双重校验验证码，登录全断");
			});
	}

	@Test
	@DisplayName("namespaces[0].value 显式配成空串 = 多进程形态（与安全链的判空口径一致）")
	void blankNamespaceValueRegistersNothing() {
		this.runner.withPropertyValues("zhiflow.path-prefix.namespaces[0].value=").run(context -> {
			assertNull(context.getStartupFailure(), "上下文没起来");
			assertTrue(context.getBeansOfType(FilterRegistrationBean.class).isEmpty(),
					"空 value 被当成了多命名空间形态 —— 判据必须与 MULTI_NAMESPACE_CONDITION 一致");
		});
	}

	private static int order(FilterRegistrationBean<?> registration) {
		return registration.getOrder();
	}

	/** 三个 registration @Bean 需要的协作者。全部真实对象，不引入 Mockito（JDK25 TypeCache 前科）。 */
	@Configuration(proxyBeanMethods = false)
	static class StubDependencies {

		@Bean
		ObjectMapper objectMapper() {
			return new JsonMapper();
		}

		@Bean("redisTemplate")
		RedisTemplate<String, Object> redisTemplate() {
			// 只为满足注入；本测试不触发任何 redis 访问。
			// afterPropertiesSet 要求非空 ConnectionFactory，给一个永不被调用的代理。
			RedisTemplate<String, Object> template = new RedisTemplate<>();
			template.setConnectionFactory((org.springframework.data.redis.connection.RedisConnectionFactory) java.lang.reflect.Proxy
				.newProxyInstance(GatewayParityRegistrationTest.class.getClassLoader(),
						new Class<?>[] { org.springframework.data.redis.connection.RedisConnectionFactory.class },
						(proxy, method, args) -> {
							throw new UnsupportedOperationException("本测试不应触发 redis 访问: " + method.getName());
						}));
			return template;
		}

		@Bean
		CacheStore cacheStore() {
			return new CacheStore() {

				@Override
				public AnyMap getCache(String key) {
					return null;
				}

				@Override
				public AnyMap getAndRemoveCache(String key) {
					return null;
				}

				@Override
				public boolean setCache(String key, AnyMap data, Long expire, TimeUnit timeUnit) {
					return true;
				}

				@Override
				public Long incr(String key, long delta, Long expire, TimeUnit timeUnit) {
					return null;
				}

				@Override
				public Long getLong(String key) {
					return null;
				}

			};
		}

	}

}
