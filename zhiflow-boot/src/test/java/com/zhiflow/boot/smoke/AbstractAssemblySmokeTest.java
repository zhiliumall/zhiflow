package com.zhiflow.boot.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单体装配冒烟门禁的公共部分（子类 {@code AllAssemblySmokeTest}）。
 *
 * <h2>两条判据</h2>
 * <ol>
 * <li><b>上下文起得来</b> —— 抓 bean 依赖图、安全链、MVC 映射、servlet 注册这一类编译期发现不了的冲突。</li>
 * <li><b>契约本地实现真的接管了对应契约接口</b> —— {@code getBean(契约接口)} 拿回来的必须<b>就是</b>
 *     本地实现那个类：bean 名相撞、有人加了第二个实现时，注入点会拿错或拿不到唯一 bean。</li>
 * </ol>
 *
 * <h2>⚠️ 本门禁不覆盖什么</h2>
 * <ul>
 * <li><b>外部依赖真的通</b>：PostgreSQL 换成内存库（见 test 的 {@code application-smoke.yml}），只连本机 Redis。
 *     纪律是<b>只挡外部网络连接，不打桩任何 Spring 装配</b> —— 一旦开始 mock 安全链或
 *     bean 依赖，这条门禁就退化成假绿。</li>
 * </ul>
 */
abstract class AbstractAssemblySmokeTest {

	@Autowired
	private ApplicationContext context;

	/** 本 profile 的装配类。扫描面从它的 {@code @ComponentScan} 现读，不在测试里另写一份。 */
	protected abstract Class<?> assemblyClass();

	@Test
	@DisplayName("上下文起得来（单体形态唯一的启动级判据）")
	void contextLoads() {
		assertNotNull(this.context, "上下文没起来");
	}

	@Test
	@DisplayName("判据自检：本 profile 的扫描面里确实有契约本地实现（0 个的话下面那条是假绿）")
	void localImplsExistInScanScope() {
		Map<Class<?>, Class<?>> found = localImplsByContract();
		assertFalse(found.isEmpty(), () -> """
				在 %s 声明的扫描面里一个契约本地实现都没找到。
				要么这个 profile 本来就不含带本地实现的服务（那本子类不该继承本基类），
				要么扫描面读错了 —— 后者会让下面那条断言静默变成永远通过。""".formatted(
						assemblyClass().getSimpleName()));
		// 数量落在日志里而不是断言里：写死一个期望值会在下次加本地实现时假红，
		// 但「扫到几个」是判断这条门禁有没有在干活的唯一线索，必须看得见。
		System.out.printf("[%s] 扫描面内的契约本地实现 %d 个：%s%n",
				assemblyClass().getSimpleName(), found.size(),
				found.values().stream().map(Class::getSimpleName).sorted().toList());
	}

	@Test
	@DisplayName("每个契约接口注入到的都是它的本地实现")
	void localImplsTakeOverTheirContracts() {
		List<String> offenders = new ArrayList<>();
		localImplsByContract().forEach((contract, localImpl) -> {
			Object resolved;
			try {
				resolved = this.context.getBean(contract);
			}
			catch (RuntimeException ex) {
				// 多个候选且没有唯一 @Primary 时是 NoUniqueBeanDefinitionException —— 正是要抓的形态之一
				offenders.add("%s → 取不到唯一 bean：%s".formatted(contract.getName(), ex.getMessage()));
				return;
			}
			if (!localImpl.isInstance(resolved)) {
				offenders.add("%s → 实际注入 %s，期望 %s".formatted(
						contract.getName(), resolved.getClass().getName(), localImpl.getName()));
			}
		});
		assertTrue(offenders.isEmpty(), () -> """
				这些契约接口在单体里没有被本地实现接管：
				%s
				先查 bean 名有没有相撞、是否有人给同一个契约加了第二个实现。""".formatted(
						String.join("\n", offenders)));
	}

	/**
	 * 现算「本 profile 扫描面内的 契约接口 → 本地实现」映射。
	 *
	 * <p>扫描面取自装配类的 {@code @ComponentScan.basePackages}，**不在测试里另写一份包名清单**：
	 * 写死一份的话，装配类哪天加了个服务、这里不跟着改，门禁就会漏掉那个服务的全部本地实现
	 * 而依然全绿。
	 *
	 * <p>⚠️ 读面必须<b>连同 {@code excludeFilters}（ASSIGNABLE_TYPE）一起</b>照装配类现读。
	 * im-ws 把 {@code ImTenantLocalApi}（DDL 契约的进程内入口）排除出装配 —— 只读 basePackages
	 * 会把这个「正确的排除」当成扫描面的一员，进而把「契约没被它接管」误报成失败；
	 * 而那个契约在 im-ws 里**就该**不被本地接管（见 {@code ImWsAssemblySmokeTest}）。
	 *
	 * <p>契约接口的判据是「接口上带 {@link HttpExchange}」而不是包名前缀 —— 契约接口散在
	 * 多个包下（{@code MallGoodsSpuApi} 曾经甚至定义在 live-admin 模块里，P3-2 已搬去
	 * {@code zhiflow-api-mall-portal}），按包名列举必漏。
	 */
	private Map<Class<?>, Class<?>> localImplsByContract() {
		return localImplsByContract(assemblyClass());
	}

	/** 静态版：按装配类的 {@code @ComponentScan} 现读扫描面。 */
	static Map<Class<?>, Class<?>> localImplsByContract(Class<?> assemblyClass) {
		ComponentScan scan = assemblyClass.getAnnotation(ComponentScan.class);
		assertNotNull(scan, assemblyClass.getSimpleName() + " 上没有 @ComponentScan，扫描面无从读起");
		String[] basePackages = scan.basePackages().length > 0 ? scan.basePackages() : scan.value();
		assertTrue(basePackages.length > 0, assemblyClass.getSimpleName() + " 的 @ComponentScan 没有 basePackages");

		List<Class<?>> excluded = new ArrayList<>();
		for (ComponentScan.Filter filter : scan.excludeFilters()) {
			if (filter.type() == org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE) {
				excluded.addAll(List.of(filter.classes()));
			}
		}

		ClassPathScanningCandidateComponentProvider scanner =
				new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

		Map<Class<?>, Class<?>> byContract = new LinkedHashMap<>();
		for (String basePackage : basePackages) {
			scanner.findCandidateComponents(basePackage).forEach(definition -> {
				Class<?> type = resolve(definition.getBeanClassName());
				if (type == null || type.isInterface() || !type.getSimpleName().endsWith("LocalApi")) {
					return;
				}
				// ASSIGNABLE_TYPE 的语义是「该类型及其子类型」，照原样用 isAssignableFrom
				if (excluded.stream().anyMatch(e -> e.isAssignableFrom(type))) {
					return;
				}
				for (Class<?> itf : type.getInterfaces()) {
					if (itf.isAnnotationPresent(HttpExchange.class)) {
						byContract.put(itf, type);
					}
				}
			});
		}
		return byContract;
	}

	private static Class<?> resolve(String className) {
		try {
			return Class.forName(className, false, AbstractAssemblySmokeTest.class.getClassLoader());
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return null;
		}
	}
}
