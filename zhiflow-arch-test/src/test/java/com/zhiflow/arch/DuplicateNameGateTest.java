package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 阶段 3 装配的重名守卫（v3 P2.0-3，第 7 阻塞点）。
 *
 * <p><b>为什么不用 ArchUnit</b>：其余门禁扫的是 {@code target/classes}，而重名恰恰是
 * 「两个模块产出同一个名字」——ArchUnit 的 importer 把它们并成一个 {@code JavaClass}，
 * 冲突在导入那一刻就消失了。所以本规则必须直接读<b>源文件树</b>。
 *
 * <p><b>为什么写成测试而不是外部脚本</b>：把规则写成本模块的一个测试，才能保证它随
 * {@code mvnw test -pl zhiflow-arch-test} 一起跑、天然进 CI；写成独立脚本或单独的
 * workflow 文件，很容易被漏接或忘记维护，变成一条没人执行的哑规则。
 *
 * <p>人可读的完整报告（含每组冲突的文件清单、是否逐字节相同）由
 * {@code tools/dup-scan/scan_duplicates.py} 产出，判据与本测试一致。
 *
 * <h2>两类冲突</h2>
 * <ol>
 *   <li><b>重复 FQCN（硬门禁，必须 0）</b>：两个模块产出同一个 {@code package.ClassName}。
 *       合并 classpath 后一个静默遮蔽另一个，谁赢取决于 jar 顺序。</li>
 *   <li><b>重复 classpath 资源路径（冻结上限）</b>：两个模块在 {@code src/main/resources/}
 *       下的同一路径各放一个文件。同样是遮蔽，但 JVM 连警告都不给。
 *       仓库起点非零（见下），驱到 0 是阶段 3 的工作，本测试只保证<b>不再变坏</b>。</li>
 * </ol>
 *
 * <p><b>fail-closed</b>：扫不到源文件一律失败。门禁最危险的失效模式不是误报，
 * 而是静默扫到 0 个文件后「通过」（与 {@link ArchScope} 同一条原则）。
 */
class DuplicateNameGateTest {

	/**
	 * 仓库根：测试的工作目录是本模块目录。
	 *
	 * <p>必须 {@code normalize()} 成绝对路径——保留 {@code ".."} 会让下面的
	 * 「跳过点开头目录」规则把根自己当成隐藏目录，扫描结果恒为空。
	 */
	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;",
			Pattern.MULTILINE);

	/** 构建产物、依赖缓存与 git worktree（{@code .claude/worktrees} 下有整份仓库副本）。 */
	private static final Set<String> SKIP_DIRS = Set.of("target", "node_modules", "dist", "logs");

	/**
	 * Spring 按设计跨 jar 合并、或每个 Boot 应用本就各自持有一份的资源。
	 * 它们同路径不是缺陷，故不计入冻结数。
	 */
	private static final List<String> STRUCTURAL_RESOURCE_PREFIXES = List.of(
			"META-INF/spring/", "META-INF/spring.factories", "META-INF/services/");

	private static final Set<String> STRUCTURAL_RESOURCE_NAMES = Set.of(
			"application.yml", "application.yaml", "application.properties",
			"bootstrap.yml", "bootstrap.yaml", "logback-spring.xml", "banner.txt");

	/**
	 * 非结构性资源路径冲突的冻结上限。
	 *
	 * <p><b>只允许调小。</b>调大意味着又有人往两个模块塞了同名资源，那正是阶段 3 装配要撞的墙。
	 *
	 * <p>变更历史：
	 * <ul>
	 *   <li>2026-08-14 P2.0-3 初次冻结：<b>88</b>。
	 *       其中 87 条是 {@code mall-api} 与 {@code mall-admin} 各自
	 *       {@code src/main/resources/mapper/} 下的同名 MyBatis XML，
	 *       <b>逐字节对比 identical=0，即 87 组全部内容发散</b>——
	 *       合并产物时同路径只能存活一份，另一份的 SQL 直接消失。
	 *       第 88 条是 {@code i18n/messages_zh_CN.properties}
	 *       （common-core 与 common-security 各一份，差 1 行文案），
	 *       这一条<b>今天就在生效</b>：两个 jar 同时在每个服务的 classpath 上，
	 *       {@code ReloadableResourceBundleMessageSource} 只会取到先被 classloader 找到的那份。
	 *       </li>
	 *   <li>2026-08-15 P2.1-B 降到 <b>1</b>：那 87 组 mapper XML 已按服务挪进模块子目录
	 *       （{@code mapper/mall-api/} 与 {@code mapper/mall-admin/}），同路径冲突随之消失。
	 *       {@code mapper-locations} 同步改成 {@code classpath*:/mapper/**}{@code /*Mapper.xml}
	 *       （{@code **} 匹配零个或多个目录，故对未挪的扁平布局同样成立）。
	 *       <b>剩下的 1 组是 {@code i18n/messages_zh_CN.properties}</b>——它不是「还没挪」，
	 *       而是两个 common jar 各持一份、今天就在生效的真实竞争，
	 *       要修得合并成一份（保留 common-security 那份的措辞），属独立排期。</li>
	 * </ul>
	 */
	private static final int RESOURCE_COLLISION_CEILING = 1;

	@Test
	@DisplayName("跨模块重复 FQCN 必须为 0（阶段 3 装配阻塞点）")
	void noDuplicateFullyQualifiedClassNames() throws IOException {
		Map<String, List<Path>> byFqcn = new TreeMap<>();
		for (Path java : sourceFiles("src/main/java", ".java")) {
			byFqcn.computeIfAbsent(fqcnOf(java), key -> new ArrayList<>()).add(java);
		}

		// 2026-10-20 开源分支删掉大批功能后实测 995 个
		assertTrue(byFqcn.size() > 800,
				"只扫到 " + byFqcn.size() + " 个主源码类，远低于本仓规模——"
						+ "说明扫描路径或工作目录不对。门禁宁可炸掉也不能静默放行。");

		List<String> duplicates = byFqcn.entrySet().stream()
				.filter(entry -> entry.getValue().size() > 1)
				.map(entry -> "  %s%n%s".formatted(entry.getKey(),
						entry.getValue().stream()
								.map(path -> "      " + REPO_ROOT.relativize(path))
								.sorted()
								.reduce((a, b) -> a + System.lineSeparator() + b)
								.orElse("")))
				.toList();

		if (!duplicates.isEmpty()) {
			fail("""
					发现 %d 个跨模块重复 FQCN。合并 classpath 后一个会静默遮蔽另一个，
					谁生效取决于 jar 顺序 —— 这是阶段 3 装配的硬阻塞点，必须改名而不是加豁免。
					%s""".formatted(duplicates.size(), String.join(System.lineSeparator(), duplicates)));
		}
	}

	@Test
	@DisplayName("非结构性 classpath 资源路径冲突不得增加（冻结上限，只降不升）")
	void resourcePathCollisionsDoNotGrow() throws IOException {
		Map<String, List<Path>> byPath = new TreeMap<>();
		for (Path resource : sourceFiles("src/main/resources", "")) {
			byPath.computeIfAbsent(classpathPathOf(resource), key -> new ArrayList<>()).add(resource);
		}

		// 2026-10-21 开源分支阶段 6：182 个历史迁移脚本压成 4 个 V1 基线后实测 92 个（git ls-files 独立计数一致）
		assertTrue(byPath.size() > 60,
				"只扫到 " + byPath.size() + " 个 classpath 资源，远低于本仓规模——扫描路径不对。");

		Map<String, List<Path>> collisions = new LinkedHashMap<>();
		byPath.forEach((cpPath, paths) -> {
			if (paths.size() > 1 && !isStructural(cpPath)) {
				collisions.put(cpPath, paths);
			}
		});

		if (collisions.size() > RESOURCE_COLLISION_CEILING) {
			List<String> added = collisions.entrySet().stream()
					.map(entry -> "  %s  (%d 份)".formatted(entry.getKey(), entry.getValue().size()))
					.toList();
			fail("""
					非结构性 classpath 资源路径冲突 %d 组，超过冻结上限 %d。
					又有同名资源被放进了两个模块——阶段 3 合并时同路径只能存活一份。
					完整清单跑 python3 tools/dup-scan/scan_duplicates.py 查看。
					%s""".formatted(collisions.size(), RESOURCE_COLLISION_CEILING,
					String.join(System.lineSeparator(), added)));
		}

		if (collisions.size() < RESOURCE_COLLISION_CEILING) {
			fail("""
					冲突降到 %d 组（冻结上限 %d）。这是好事——请把 RESOURCE_COLLISION_CEILING
					改成 %d 并在 javadoc 变更历史里记一行，让进展留下痕迹而不是悄悄漂移。"""
					.formatted(collisions.size(), RESOURCE_COLLISION_CEILING, collisions.size()));
		}
	}

	private static boolean isStructural(String classpathPath) {
		return STRUCTURAL_RESOURCE_NAMES.contains(classpathPath)
				|| STRUCTURAL_RESOURCE_PREFIXES.stream().anyMatch(classpathPath::startsWith);
	}

	/** 全仓所有模块 {@code <module>/<sourceRoot>/} 下的文件（按后缀过滤，空后缀表示全要）。 */
	private static List<Path> sourceFiles(String sourceRoot, String suffix) throws IOException {
		String marker = "/" + sourceRoot.replace('\\', '/') + "/";
		try (Stream<Path> walk = Files.walk(REPO_ROOT)) {
			return walk
					.filter(path -> !isSkipped(path))
					.filter(Files::isRegularFile)
					.filter(path -> normalize(path).contains(marker))
					.filter(path -> suffix.isEmpty() || path.getFileName().toString().endsWith(suffix))
					.toList();
		}
	}

	/** 只对仓库根<b>以下</b>的路径段判定，避免把根路径自身的目录名误当成跳过项。 */
	private static boolean isSkipped(Path path) {
		for (Path segment : REPO_ROOT.relativize(path)) {
			String name = segment.toString();
			if (SKIP_DIRS.contains(name) || name.startsWith(".")) {
				return true;
			}
		}
		return false;
	}

	private static String normalize(Path path) {
		return path.toString().replace('\\', '/');
	}

	private static String fqcnOf(Path javaFile) {
		String source = read(javaFile);
		Matcher matcher = PACKAGE.matcher(source);
		String simple = javaFile.getFileName().toString();
		simple = simple.substring(0, simple.length() - ".java".length());
		return matcher.find() ? matcher.group(1) + "." + simple : simple;
	}

	private static String classpathPathOf(Path resource) {
		String normalized = normalize(resource);
		int index = normalized.indexOf("/src/main/resources/");
		return normalized.substring(index + "/src/main/resources/".length());
	}

	private static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			try {
				return new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
			}
			catch (IOException fallback) {
				throw new UncheckedIOException(fallback);
			}
		}
	}
}
