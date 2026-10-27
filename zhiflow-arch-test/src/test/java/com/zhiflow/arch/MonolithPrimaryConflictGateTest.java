package com.zhiflow.arch;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [2026-09-13] 单体 {@code all} 装配里，同一个契约接口<b>不得有两个存活的 {@code @Primary} 实现</b>。
 *
 * <h2>这条为什么必须是门禁</h2>
 * <p>多进程形态下每个服务各跑各的，同名冲突不会发生；单体把 8 个服务装进一个容器后，
 * 两个 {@code @Primary} 的同接口实现会在<b>启动时</b>撞成
 * {@code NoUniqueBeanDefinitionException} —— 单体直接起不来。
 *
 * <p>而 {@code @Primary} 是<b>被别的门禁强制加上的</b>：{@code ContractLocalImplGateTest}
 * 的 R-C3 要求契约本地实现与同接口装饰器都必须 {@code @Primary}。也就是说
 * <b>「按规矩写代码」本身就会制造这个冲突</b>，只能靠 {@code AllAssembly} 的 excludeFilters 逐个排掉。
 * 排漏了不会编译报错、不会有任何静态提示，只在单体启动那一刻炸。
 *
 * <p>历史上已经踩过两次：
 * <ul>
 * <li>2026-08-21 {@code ResilientTenantApi} / {@code ResilientConfigStorageApi}
 *     与 upms 的 {@code TenantLocalApi} / {@code ConfigStorageLocalApi} 相撞；</li>
 * <li>2026-09-13 {@code AiGoodsApiRouter}（im）与 {@code AiGoodsLocalApi}（mall-api）相撞 ——
 *     这次是<b>靠人读 AllAssembly 的旧注释推断出来的</b>，没有任何测试拦得住。本门禁补的就是这个缺口。</li>
 * </ul>
 *
 * <h2>判据</h2>
 * <p>源码级：从 {@code AllAssembly} 现读 {@code basePackages} 与 {@code excludeFilters}，
 * 再扫这些包下带 {@code @Primary} 且 {@code implements *Api} 的类，
 * 按接口分组后断言「排除之后每个接口最多剩一个」。
 * <b>不读 target/classes</b>：{@code ArchScope} 的扫描面不含 im 的全部模块，且契约接口在那里是空壳
 * （{@code ContractLocalImplGateTest} 的 javadoc 记过这个陷阱）。
 */
class MonolithPrimaryConflictGateTest {

	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	private static final Path ALL_ASSEMBLY = REPO_ROOT
			.resolve("zhiflow-boot/src/main/java/com/zhiflow/boot/assembly/AllAssembly.java");

	/** {@code class X ... implements A, B {} } —— 取 implements 到第一个 { 之间。 */
	private static final Pattern IMPLEMENTS = Pattern
			.compile("class\\s+(\\w+)[^{]*?\\bimplements\\s+([\\w,\\s.<>]+?)\\{", Pattern.DOTALL);

	private static List<String> scannedPackages;

	private static Set<String> excludedSimpleNames;

	/** 契约接口简单名 → 该接口在扫描面内的 @Primary 实现类简单名。 */
	private static Map<String, List<String>> primariesByContract;

	@BeforeAll
	static void scan() {
		String assembly = read(ALL_ASSEMBLY);
		scannedPackages = new ArrayList<>();
		Matcher pkg = Pattern.compile("\"(com\\.zhiflow\\.[\\w.]+)\"").matcher(basePackagesBlock(assembly));
		while (pkg.find()) {
			scannedPackages.add(pkg.group(1));
		}
		excludedSimpleNames = new LinkedHashSet<>();
		Matcher ex = Pattern.compile("([A-Za-z0-9_]+)\\.class").matcher(assembly);
		while (ex.find()) {
			excludedSimpleNames.add(ex.group(1));
		}

		primariesByContract = new LinkedHashMap<>();
		for (Path file : javaFiles()) {
			String source = read(file);
			if (!source.contains("@Primary")) {
				continue;
			}
			Matcher pm = Pattern.compile("package\\s+([\\w.]+);").matcher(source);
			if (!pm.find()) {
				continue;
			}
			String pkgName = pm.group(1);
			if (scannedPackages.stream().noneMatch(pkgName::startsWith)) {
				continue;
			}
			String stripped = JavaSource.stripComments(source);
			Matcher im = IMPLEMENTS.matcher(stripped);
			if (!im.find()) {
				continue;
			}
			String cls = im.group(1);
			for (String raw : im.group(2).split(",")) {
				String itf = raw.trim().split("<")[0];
				itf = itf.substring(itf.lastIndexOf('.') + 1);
				if (itf.endsWith("Api")) {
					primariesByContract.computeIfAbsent(itf, k -> new ArrayList<>()).add(cls);
				}
			}
		}
	}

	// ================================================================= 判据自检

	@Test
	@DisplayName("自检：读得到 AllAssembly 的 basePackages 与 excludeFilters（读空 = 下面那条是假绿）")
	void assemblyIsParsed() {
		assertTrue(scannedPackages.size() >= 5,
				"只从 AllAssembly 解析出 " + scannedPackages.size() + " 个 basePackage，解析失效了：" + scannedPackages);
		assertFalse(excludedSimpleNames.isEmpty(), "excludeFilters 一个都没解析出来，排除逻辑会被当成不存在");
	}

	@Test
	@DisplayName("自检：扫到足够多的 @Primary 契约实现（扫空 = 下面那条什么都没检查）")
	void primariesAreFound() {
		assertTrue(primariesByContract.size() >= 10, () -> """
				只扫到 %d 个「有 @Primary 实现」的契约接口，远少于实测的 13 个（2026-10-20 开源分支）。
				扫描面或正则失效了 —— 此时下面那条冲突断言会静默通过。""".formatted(primariesByContract.size()));
	}

	// ================================================================= 本体

	@Test
	@DisplayName("⭐ 单体 all 装配里，每个契约接口排除后最多剩一个 @Primary 实现（两个 = 启动即 NoUniqueBeanDefinitionException）")
	void noContractHasTwoLivePrimariesInTheMonolith() {
		List<String> offenders = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : primariesByContract.entrySet()) {
			List<String> live = e.getValue().stream().filter(c -> !excludedSimpleNames.contains(c)).toList();
			if (live.size() > 1) {
				offenders.add(e.getKey() + " -> " + live + "（全部 @Primary 实现：" + e.getValue() + "）");
			}
		}
		assertTrue(offenders.isEmpty(), () -> """
				这些契约接口在单体 all 装配里有多个存活的 @Primary 实现：
				%s

				后果：单体启动时 NoUniqueBeanDefinitionException，**直接起不来**。
				多进程形态看不出任何问题，所以只能靠本门禁在构建期拦。

				修法：在 AllAssembly 的 excludeFilters 里排掉**其中一个**，并在那里写清
				「为什么排这个而不是那个」—— 判据是「它保护的失效模式在单体里存不存在」。
                                照 ResilientTenantApi / AiGoodsApiRouter 两处既有注释的写法。""".formatted(
						String.join("\n", offenders)));
	}

	// ================================================================= helpers

	private static String basePackagesBlock(String assembly) {
		int start = assembly.indexOf("basePackages");
		if (start < 0) {
			return "";
		}
		int end = assembly.indexOf("excludeFilters", start);
		return end < 0 ? assembly.substring(start) : assembly.substring(start, end);
	}

	private static List<Path> javaFiles() {
		try (Stream<Path> files = Files.walk(REPO_ROOT)) {
			return files.filter(f -> f.toString().endsWith(".java"))
					.filter(f -> !f.toString().contains("/target/"))
					.filter(f -> !f.toString().contains("/.claude/"))
					// 只扫 src/main/java：测试源码里出现的判据字符串会让规则自我满足
					// （GrpcInsideInterceptorGateTest 第一版就是这么假绿的）。
					.filter(f -> f.toString().contains("/src/main/java/"))
					.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
