package com.zhiflow.mall.api.config;

import com.zhiflow.mall.common.orderno.OrderNoGeneratorAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mall-api 侧的装配可见性验证。
 *
 * <p>本服务有 5 个 {@link com.zhiflow.mall.common.orderno.OrderNoGenerator} 注入点
 * （ConsultOrderController / CashierOrderController / SeckillOrderController /
 * ConsultTailServiceImpl / OrderInfoServiceImpl），而
 * {@code ZhiflowMallApiApplication} 的 {@code @ComponentScan} **不含**
 * {@code com.zhiflow.mall.common}——组件只能靠 mall-common 的
 * {@code META-INF/spring/...AutoConfiguration.imports} 注册。
 *
 * <p>该文件若漏行或没被打进依赖 jar，编译与普通单测**全绿**，一启动才因找不到 Bean 崩溃。
 * 本测试就断言这一条：从 mall-api 自己的 classpath 上看得见那行注册。
 *
 * <p>装配行为本身（默认机器位 / T2 替换位）由 mall-admin 侧的
 * {@code OrderNoGeneratorAutoConfigurationTest} 覆盖——是同一个自动配置类，不重复测。
 */
class OrderNoGeneratorImportsVisibleTest {

	@Test
	void autoConfigurationIsVisibleOnMallApiClasspath() {
		List<String> candidates = new ArrayList<>();
		ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
				.forEach(candidates::add);

		assertTrue(candidates.contains(OrderNoGeneratorAutoConfiguration.class.getName()),
				"mall-api classpath 上看不到 OrderNoGeneratorAutoConfiguration 的注册，"
						+ "本服务 5 个 OrderNoGenerator 注入点会在启动期全部失败。实际注册项：" + candidates);
	}
}
