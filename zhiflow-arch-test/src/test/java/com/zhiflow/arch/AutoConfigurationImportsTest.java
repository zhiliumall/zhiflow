/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每个模块 {@code META-INF/spring/*.AutoConfiguration.imports} 里登记的类都必须还在源码里。
 *
 * <p>删类时容易漏掉这份清单：编译和单元测试都发现不了，打出来的 jar 启动时报
 * 「Unable to read meta-data for class …」直接起不来。
 */
class AutoConfigurationImportsTest {

	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	@Test
	@DisplayName("AutoConfiguration.imports 登记的类都存在")
	void everyImportedClassExists() throws IOException {
		List<Path> importFiles;
		Set<String> classes;
		try (Stream<Path> all = Files.walk(REPO_ROOT, 12)) {
			List<Path> files = all.filter(Files::isRegularFile)
				.filter(p -> !p.toString().contains("/target/"))
				.toList();
			importFiles = files.stream()
				.filter(p -> p.toString().contains("/src/main/resources/META-INF/spring/")
						&& p.getFileName().toString().endsWith(".imports"))
				.toList();
			classes = files.stream()
				.map(Path::toString)
				.filter(s -> s.contains("/src/main/java/") && s.endsWith(".java"))
				.map(s -> s.substring(s.indexOf("/src/main/java/") + "/src/main/java/".length(), s.length() - 5)
						.replace('/', '.'))
				.collect(Collectors.toSet());
		}
		assertTrue(importFiles.size() > 3, "只找到 " + importFiles.size() + " 份 imports 清单，扫描范围可能失效");

		Set<String> missing = new TreeSet<>();
		for (Path f : importFiles) {
			for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
				String name = line.strip();
				if (name.startsWith("com.zhiflow.") && !classes.contains(name)) {
					missing.add(name + "    ← " + REPO_ROOT.relativize(f));
				}
			}
		}
		assertTrue(missing.isEmpty(), () -> "这些自动配置类已经不存在，从清单里删掉：\n" + String.join("\n", missing));
	}
}
