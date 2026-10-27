package com.zhiflow.boot.smoke;

import com.zhiflow.boot.ZhiflowBootApplication;
import com.zhiflow.boot.assembly.AllAssembly;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单体（唯一的可执行 jar）的装配冒烟：主配置 + {@code smoke} profile（只把 PostgreSQL 换成内存库）。
 *
 * <p>契约接口在开源分支只有进程内实现（{@code *LocalApi}），没有 HTTP 代理兜底：
 * 缺实现的契约一旦有注入点，上下文直接起不来；没有注入点的由下面的反向断言抓。
 */
@SpringBootTest(classes = ZhiflowBootApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("smoke")
class AllAssemblySmokeTest extends AbstractAssemblySmokeTest {

	@Override
	protected Class<?> assemblyClass() {
		return AllAssembly.class;
	}

	@Autowired
	private ApplicationContext context;

	// ==================================================================================
	// 契约覆盖的**反向**断言：从契约接口全集出发。
	//
	// 基类那条 localImplsTakeOverTheirContracts 是从 *LocalApi 出发建映射的 ——
	// 它只能验「已有的实现是否接管」，结构上看不见「哪个契约根本没有实现」。
	// 2026-08-16 实测：契约 52 个、实现 43 个，9 个缺口在旧判据下全绿。
	//
	// 开源分支没有 HTTP 代理：缺实现的契约在有注入点时上下文起不来，没有注入点时就是死接口。
	// ==================================================================================

	@Test
	@DisplayName("契约接口全集：每一个要么有本地实现，要么在待办表里登记了理由")
	void everyContractIsLocallyImplementedOrRegisteredAsBacklog() {
		List<String> uncovered = new ArrayList<>();
		Set<String> contracts = allContractInterfaces();
		for (String contractName : contracts) {
			Class<?> contract = resolveClass(contractName);
			if (contract == null) {
				continue;
			}
			Object resolved;
			try {
				resolved = this.context.getBean(contract);
			}
			catch (RuntimeException ex) {
				resolved = null;
			}
			// ⚠️ 判据是 getName().contains 而不是 getSimpleName().endsWith：
			// 带 @Transactional(REQUIRES_NEW) 的本地实现会被 Spring 包成 CGLIB 代理，
			// 类名是 XxxLocalApi$$SpringCGLIB$$0 —— endsWith 判不中，第一版就栽在这，
			// 表现为「有事务代理的实现被误报为缺失」。
			boolean locallyImplemented = resolved != null
					&& resolved.getClass().getName().contains("LocalApi");
			if (!locallyImplemented && !ContractLocalImplBacklog.PENDING.containsKey(contractName)) {
				uncovered.add(contractName);
			}
		}
		assertTrue(uncovered.isEmpty(), () -> """
				这些契约接口在 all 形态下既没有本地实现、也没在 ContractLocalImplBacklog 登记：
				%s
				要么补本地实现，要么删掉这个契约（开源分支没有 HTTP 代理兜底）。""".formatted(String.join("\n", uncovered)));
	}

	@Test
	@DisplayName("判据自检：契约全集扫描必须接近实测的 13 个（扫空 = 上一条是假绿）")
	void contractUniverseScanIsNotEmpty() {
		int size = allContractInterfaces().size();
		// 2026-10-20 开源分支删掉大批契约后实测 13 个
		assertTrue(size >= 10, "只扫到 " + size + " 个契约接口，远少于实测的 13 —— 扫描面失效，上一条断言毫无意义");
	}

	@Test
	@DisplayName("守卫：待办表里不许留下已经有本地实现的条目（补齐一个删一行）")
	void backlogHasNoStaleEntries() {
		List<String> stale = new ArrayList<>();
		for (String contractName : ContractLocalImplBacklog.PENDING.keySet()) {
			Class<?> contract = resolveClass(contractName);
			if (contract == null) {
				stale.add(contractName + "（类已不存在）");
				continue;
			}
			try {
				Object resolved = this.context.getBean(contract);
				if (resolved.getClass().getName().contains("LocalApi")) {
					stale.add(contractName + "（本地实现已就位）");
				}
			}
			catch (RuntimeException ignored) {
				// 取不到唯一 bean = 还没接管，条目仍然有效
			}
		}
		assertTrue(stale.isEmpty(), () -> """
				这些待办条目已经过时，却还留在 ContractLocalImplBacklog 里：%s
				死条目会让下一个人以为缺口还在。删掉它们。""".formatted(stale));
	}

	/**
	 * 契约接口全集：全仓扫 {@code @HttpExchange} 接口，不写死包名清单（写死必漏）。
	 */
	static Set<String> allContractInterfaces() {
		ClassPathScanningCandidateComponentProvider scanner =
				new ClassPathScanningCandidateComponentProvider(false) {
					@Override
					protected boolean isCandidateComponent(
							org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
						// 契约是接口；默认实现会把接口过滤掉，这里放开
						return beanDefinition.getMetadata().isInterface();
					}
				};
		scanner.addIncludeFilter(new AnnotationTypeFilter(HttpExchange.class));
		Set<String> found = new LinkedHashSet<>();
		for (BeanDefinition bd : scanner.findCandidateComponents("com.zhiflow")) {
			found.add(bd.getBeanClassName());
		}
		return found;
	}

	private static Class<?> resolveClass(String name) {
		try {
			return Class.forName(name, false, AllAssemblySmokeTest.class.getClassLoader());
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return null;
		}
	}
}
