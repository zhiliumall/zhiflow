package com.zhiflow.mall.admin.config;

import com.zhiflow.mall.common.orderno.FixedWorkerIdAssigner;
import com.zhiflow.mall.common.orderno.OrderNoGenerator;
import com.zhiflow.mall.common.orderno.OrderNoGeneratorAutoConfiguration;
import com.zhiflow.mall.common.orderno.WorkerIdAssigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrderNoGenerator} 的装配验证。
 *
 * <p><b>为什么必须单独测这个</b>：mall-api / mall-admin 两个启动类的 {@code @ComponentScan}
 * 都不含 {@code com.zhiflow.mall.common}，本组件只能靠
 * {@code META-INF/spring/...AutoConfiguration.imports} 注册。那个文件写错、漏行或没被打进产物，
 * **编译和普通单测全绿，一启动才炸**——本仓已有同类事故（见 {@code BeanConstructorAuditTest}）。
 */
class OrderNoGeneratorAutoConfigurationTest {

	// 带上 ConfigurationPropertiesAutoConfiguration：逃生舱靠配置回滚，
	// 那条绑定路径必须真实走一遍——否则测试里 @ConfigurationProperties 不生效，
	// 会得出「配了也没用」却查不出原因的假象。
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(
					ConfigurationPropertiesAutoConfiguration.class,
					OrderNoGeneratorAutoConfiguration.class));

	/**
	 * 第一层判据：自动配置确实注册在 imports 文件里，且该文件在 classpath 上可读。
	 * 这条才防得住「文件没写对 / 没被打包」，{@link AutoConfigurations#of} 是显式传类，防不住。
	 */
	@Test
	void autoConfigurationIsRegisteredInImportsFile() {
		List<String> candidates = new ArrayList<>();
		ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
				.forEach(candidates::add);

		assertThat(candidates)
				.as("OrderNoGeneratorAutoConfiguration 必须注册进 AutoConfiguration.imports，"
						+ "否则 mall-api / mall-admin 都注入不到 OrderNoGenerator（ComponentScan 不含 mall.common）")
				.contains(OrderNoGeneratorAutoConfiguration.class.getName());
	}

	/**
	 * 无 DataSource 时落回固定机器位。
	 *
	 * <p>mall-common 是库模块，不能假定每个引用方都配了数据源——若此时也 fail-fast，
	 * 会把「本来就不该用注册表的场景」一起拖垮。注册表的 fail-fast 只在真要用它时生效。
	 */
	@Test
	void fallsBackToFixedBitsWhenNoDataSourceIsPresent() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(OrderNoGenerator.class);
			assertThat(context).hasSingleBean(WorkerIdAssigner.class);

			OrderNoGenerator generator = context.getBean(OrderNoGenerator.class);
			assertThat(generator.getWorkerId()).isZero();
			assertThat(generator.getDataCenterId()).isZero();
			assertThat(generator.next()).matches("\\d{19}");
		});
	}

	/**
	 * 逃生舱：{@code orderno.worker-id-strategy=fixed} 退回固定机器位，且可指定取值。
	 * 这是回滚路径——不需要回滚 DDL，改一行配置即可。
	 */
	@Test
	void escapeHatchSwitchesBackToFixedMachineBits() {
		runner.withPropertyValues(
				"orderno.worker-id-strategy=fixed",
				"orderno.fixed-worker-id=7",
				"orderno.fixed-data-center-id=3").run(context -> {
			WorkerIdAssigner assigner = context.getBean(WorkerIdAssigner.class);
			assertThat(assigner).isInstanceOf(FixedWorkerIdAssigner.class);
			assertThat(assigner.getWorkerId()).isEqualTo(7L);
			assertThat(assigner.getDataCenterId()).isEqualTo(3L);
		});
	}

	/**
	 * T2 的替换位可用：外部提供 {@link WorkerIdAssigner} 时，
	 * 默认的 {@link FixedWorkerIdAssigner} 退让，{@link OrderNoGenerator} 自动改用新机器位。
	 */
	@Test
	void customAssignerOverridesTheDefaultOne() {
		runner.withUserConfiguration(CustomAssignerConfig.class).run(context -> {
			assertThat(context).hasSingleBean(WorkerIdAssigner.class);

			OrderNoGenerator generator = context.getBean(OrderNoGenerator.class);
			assertThat(generator.getWorkerId()).isEqualTo(9L);
			assertThat(generator.getDataCenterId()).isEqualTo(4L);

			long id = Long.parseLong(generator.next());
			assertThat((id >> 12) & 0x1F).isEqualTo(9L);
			assertThat((id >> 17) & 0x1F).isEqualTo(4L);
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomAssignerConfig {

		@Bean
		WorkerIdAssigner customWorkerIdAssigner() {
			return new FixedWorkerIdAssigner(9L, 4L);
		}
	}
}
