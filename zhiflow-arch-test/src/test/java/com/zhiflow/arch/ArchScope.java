package com.zhiflow.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 门禁的扫描范围。
 *
 * <p>为什么扫 target/classes 而不是把业务模块作为 Maven 依赖：业务模块被
 * spring-boot-maven-plugin repackage 成 fat jar，class 落在 BOOT-INF/classes 下，
 * ArchUnit 无法直接解析。
 *
 * <p><b>目录缺失一律硬失败。</b>门禁最危险的失效模式不是误报，而是静默扫到 0 个 class
 * 后「通过」——那会让所有回退畅通无阻。因此未编译、路径写错、模块改名都必须让测试炸掉。
 */
final class ArchScope {

	/** 含 Controller / ServiceImpl，需要纳入门禁的模块（相对仓库根） */
	private static final List<String> SCANNED_MODULES = List.of(
			"zhiflow-upms/zhiflow-upms-admin",
			"zhiflow-mall/zhiflow-mall-admin",
			"zhiflow-mall/zhiflow-mall-api",
			"zhiflow-pay/zhiflow-pay-admin",
			"zhiflow-weixin/zhiflow-weixin-admin",
			"zhiflow-auth");

	/**
	 * 实体所在模块 —— 供 S8 波次 4 的实体侧规则（R6/R7/R8）使用。
	 *
	 * <p><b>为什么单独一份而不是并入 {@link #SCANNED_MODULES}</b>：实体全在 {@code *-common} 里，
	 * 而 {@code SCANNED_MODULES} 的六条既有规则（@SysLog / 鉴权 / 事务 / 物理删 / BatchResult / 吞异常）
	 * 都已冻结了 baseline。把 common 模块并进去会改变那六条规则的扫描面，
	 * 一旦 common 里有任何类命中它们的谓词，baseline 就会漂移 —— 那是「改判据顺手放行/收紧存量」的
	 * 同一类失效模式（见 {@code BaselineIntegrityTest} 变更历史里 {@code @Inside} 那条）。
	 * 分开扫可以保证：加实体规则不动老规则一个数字。
	 */
	private static final List<String> ENTITY_MODULES = List.of(
			"zhiflow-common/zhiflow-common-core",
			"zhiflow-mall/zhiflow-mall-common",
			"zhiflow-upms/zhiflow-upms-common",
			"zhiflow-weixin/zhiflow-weixin-common",
			"zhiflow-pay/zhiflow-pay-common");

	private ArchScope() {
	}

	static JavaClasses importAll() {
		return importModules(SCANNED_MODULES);
	}

	/** 实体侧规则的扫描范围，完整性/陈旧校验与 {@link #importAll()} 完全一致 */
	static JavaClasses importEntities() {
		return importModules(ENTITY_MODULES);
	}

	private static JavaClasses importModules(List<String> modules) {
		Path root = repoRoot();
		List<Path> classDirs = new ArrayList<>();
		List<String> missing = new ArrayList<>();

		for (String module : modules) {
			Path moduleRoot = root.resolve(module);
			Path classes = moduleRoot.resolve("target/classes");
			if (!Files.isDirectory(classes)) {
				missing.add(module + " -> 缺少 " + classes);
				continue;
			}
			// 完整性判据：class 数必须 ≥ 源文件数。内部类/匿名类只会让 class 变多，
			// class 反而更少必然是编译不完整（增量编译中断、编译错误被忽略等）。
			// 用相对判据而非绝对下限：codegen 这类小模块只有 19 个 class，绝对下限会误伤。
			Path sources = moduleRoot.resolve("src/main/java");
			long classCount = count(classes, ".class");
			long sourceCount = count(sources, ".java");
			if (classCount < sourceCount) {
				missing.add("%s -> %d 个 class 少于 %d 个源文件，编译不完整".formatted(module, classCount, sourceCount));
				continue;
			}

			// 陈旧检测。数量校验挡不住「class 是旧版本代码编译出来的」——陈旧产物的 class 数量
			// 往往足够甚至更多（旧代码里的类可能已删除）。而扫陈旧 class 会让 baseline 冻结在
			// 一个不存在的代码状态上：实测 S1 首次冻结时 weixin-admin 与 im-server 就是陈旧产物，
			// 导致 8 条真实违例没进 baseline，S3/S8 的工作清单跟着少了 8 项。
			long newestSource = newestModified(sources, ".java");
			long newestClass = newestModified(classes, ".class");
			if (newestSource > newestClass) {
				missing.add("%s -> 存在比 class 更新的源文件（源 %d > class %d），编译产物陈旧"
						.formatted(module, newestSource, newestClass));
				continue;
			}
			classDirs.add(classes);
		}

		if (!missing.isEmpty()) {
			throw new IllegalStateException("""
					架构门禁无法扫描以下模块，拒绝以「部分范围」通过：
					%s

					请先全量编译被扫模块：
					  export JAVA_HOME=<temurin-25>
					  ./mvnw -B -ntp compile -DskipTests
					""".formatted(String.join("\n", missing.stream().map(m -> "  - " + m).toList())));
		}

		JavaClasses classes = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPaths(classDirs);

		if (classes.isEmpty()) {
			throw new IllegalStateException("扫描到 0 个 class，门禁失效。classDirs=" + classDirs);
		}
		return classes;
	}

	private static long count(Path dir, String suffix) {
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(p -> p.toString().endsWith(suffix)).count();
		}
		catch (Exception e) {
			throw new IllegalStateException("统计 " + dir + " 失败", e);
		}
	}

	/** 目录下指定后缀文件的最新修改时间（毫秒），无文件时返回 0 */
	private static long newestModified(Path dir, String suffix) {
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(p -> p.toString().endsWith(suffix))
					.mapToLong(p -> {
						try {
							return Files.getLastModifiedTime(p).toMillis();
						}
						catch (Exception e) {
							return 0L;
						}
					})
					.max()
					.orElse(0L);
		}
		catch (Exception e) {
			throw new IllegalStateException("检测 " + dir + " 修改时间失败", e);
		}
	}

	/**
	 * 从工作目录向上找含 {@code .git} 的仓库根，避免依赖 surefire 的 basedir 语义。
	 *
	 * <p><b>判据必须是「存在」而不是「是目录」</b>：git worktree 的根里 {@code .git} 是一个
	 * <b>文件</b>（内容形如 {@code gitdir: /path/to/main/.git/worktrees/xxx}），只有主仓库
	 * 才是目录。用 {@code isDirectory} 会让整个门禁在任何 worktree 里直接抛
	 * {@code IllegalStateException} 起不来 —— 而本仓常年开着二十多个 worktree，
	 * 且 GitHub Actions 自 2026-07-30 停跑后，「改完必须本地跑一遍门禁」是唯一还在的闸。
	 * 在 worktree 里那一步会以「环境坏了」的形态失败，很容易被当成噪声跳过。
	 */
	private static Path repoRoot() {
		Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (current != null) {
			if (Files.exists(current.resolve(".git"))) {
				return current;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("找不到仓库根（向上未发现 .git），user.dir=" + System.getProperty("user.dir"));
	}
}
