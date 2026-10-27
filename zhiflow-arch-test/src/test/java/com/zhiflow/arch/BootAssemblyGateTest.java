package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单体装配层的硬约束。这几件事错了都<b>不会在编译期报错</b>，只会在启动或运行时以最难查的形态出现。
 *
 * <h2>① 全仓只有一个 {@code @SpringBootApplication}</h2>
 * <p>业务模块的包都在 {@code AllAssembly} 的扫描面里。谁在模块里再加一个启动类，它自带的
 * {@code @ComponentScan} / {@code @MapperScan} / {@code @EnableXxx} 会被一起扫进来，
 * 扫描面与 bean 名生成器悄悄变样，而进程照样起得来。
 *
 * <h2>② {@code nameGenerator} 必须是全限定名</h2>
 * <p>{@code @ComponentScan} 与 {@code @MapperScan} 少挂一处，不同模块里同简单名的类就会互相覆盖。
 *
 * <h2>③ {@code @EnableScheduling} 只挂在 mall-api 的 {@code SchedulingConfig} 上</h2>
 * <p>{@code SchedulingConfig} 受 {@code mall.dev.scheduled-tasks-enabled} 控制；别处再挂一个，
 * 开关就静默失效（本机开发连共享库时定时任务照跑）。
 */
class BootAssemblyGateTest {

	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	private static final Path ASSEMBLY_DIR = REPO_ROOT
			.resolve("zhiflow-boot/src/main/java/com/zhiflow/boot/assembly");

	private static final String NAME_GENERATOR = "FullyQualifiedAnnotationBeanNameGenerator";

	private static final Pattern LEADING_SPRING_BOOT_APPLICATION = Pattern.compile("(?m)^@SpringBootApplication\\b");

	private static final Pattern LEADING_ENABLE_SCHEDULING = Pattern.compile("(?m)^@EnableScheduling\\b");

	private static final String BOOT_APPLICATION =
			"zhiflow-boot/src/main/java/com/zhiflow/boot/ZhiflowBootApplication.java";

	private static final String SCHEDULING_CONFIG =
			"zhiflow-mall/zhiflow-mall-api/src/main/java/com/zhiflow/mall/api/config/SchedulingConfig.java";

	@Test
	@DisplayName("判据自检：至少有一个装配类（0 个的话下面的规则全是假绿）")
	void assemblyClassesExist() throws IOException {
		assertFalse(assemblies().isEmpty(), """
				zhiflow-boot 下一个装配类都没有 —— 目录改名了的话，这条门禁会静默变成永远通过。""");
	}

	@Test
	@DisplayName("全仓只有 ZhiflowBootApplication 一个 @SpringBootApplication")
	void onlyTheBootApplicationIsSpringBootApplication() throws IOException {
		List<String> found = filesWith(LEADING_SPRING_BOOT_APPLICATION);
		assertTrue(found.contains(BOOT_APPLICATION), () -> "判据自检失败：没在 " + BOOT_APPLICATION
				+ " 上找到 @SpringBootApplication，实际找到：" + found);
		assertTrue(found.size() == 1, () -> """
				除单体启动类外还有 @SpringBootApplication：
				%s
				业务模块在 AllAssembly 的扫描面里，这些类会把自己的扫描与 @EnableXxx 一并带进单体。""".formatted(
						String.join("\n", found)));
	}

	@Test
	@DisplayName("@EnableScheduling 只挂在 SchedulingConfig 上（别处挂 = 调度开关失效）")
	void enableSchedulingOnlyOnSchedulingConfig() throws IOException {
		List<String> found = filesWith(LEADING_ENABLE_SCHEDULING);
		assertTrue(found.contains(SCHEDULING_CONFIG), () -> "判据自检失败：没在 " + SCHEDULING_CONFIG
				+ " 上找到 @EnableScheduling，实际找到：" + found);
		assertTrue(found.size() == 1, () -> """
				这些类也挂了 @EnableScheduling，会绕过 mall.dev.scheduled-tasks-enabled：
				%s""".formatted(String.join("\n", found)));
	}

	@Test
	@DisplayName("@ComponentScan 与 @MapperScan 都必须挂 FullyQualifiedAnnotationBeanNameGenerator")
	void assembliesKeepFullyQualifiedBeanNames() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path file : assemblies()) {
			String source = Files.readString(file, StandardCharsets.UTF_8);
			for (String annotation : new String[] { "@ComponentScan(", "@MapperScan(" }) {
				int at = source.indexOf(annotation);
				if (at < 0) {
					continue;
				}
				String block = balancedBlock(source, at + annotation.length() - 1);
				if (!block.contains(NAME_GENERATOR)) {
					offenders.add(file.getFileName() + " 的 " + annotation.replace("(", ""));
				}
			}
		}
		assertTrue(offenders.isEmpty(), () -> """
				这些扫描没挂 %s：
				%s
				不同模块里同简单名的类（如两个 OrderInfoServiceImpl）会互相覆盖，谁生效取决于扫描顺序。""".formatted(
						NAME_GENERATOR, String.join("\n", offenders)));
	}

	/** 全仓 src/main/java 里行首带指定注解的文件（相对仓库根）。注释里的引用不算。 */
	private static List<String> filesWith(Pattern annotation) throws IOException {
		List<String> found = new ArrayList<>();
		try (Stream<Path> files = Files.walk(REPO_ROOT)) {
			for (Path p : files.filter(f -> f.toString().endsWith(".java"))
					.filter(f -> f.toString().contains("/src/main/java/"))
					.filter(f -> !f.toString().contains("/target/"))
					// 整份仓库副本（常是别的分支），算进来会误判
					.filter(f -> !f.toString().contains("/.claude/"))
					.toList()) {
				String source = JavaSource.stripComments(Files.readString(p, StandardCharsets.UTF_8));
				if (annotation.matcher(source).find()) {
					found.add(REPO_ROOT.relativize(p).toString().replace('\\', '/'));
				}
			}
		}
		return found;
	}

	/**
	 * 从 {@code open} 处的左括号取一对**平衡**括号里的内容。
	 *
	 * <p>不能用「找第一个右括号」：{@code @ComponentScan} 里嵌着
	 * {@code @ComponentScan.Filter(...)}，第一个右括号是内层那个 ⇒ 截出来的块不含后面的
	 * {@code nameGenerator}，规则于是**对正确的代码也报错、对错误的代码可能放过**。
	 * 2026-08-16 反向验证时就是靠「只红了一条」发现这段解析不可靠的。
	 */
	private static String balancedBlock(String source, int open) {
		int depth = 0;
		for (int i = open; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '(') {
				depth++;
			}
			else if (c == ')') {
				depth--;
				if (depth == 0) {
					return source.substring(open, i + 1);
				}
			}
		}
		return source.substring(open);
	}

	private static List<Path> assemblies() throws IOException {
		if (!Files.isDirectory(ASSEMBLY_DIR)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(ASSEMBLY_DIR)) {
			return files.filter(p -> p.toString().endsWith(".java")).toList();
		}
	}
}
