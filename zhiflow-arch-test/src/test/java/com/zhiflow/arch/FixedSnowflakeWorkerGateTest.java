package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mall / upms 模块不许再用「机器位写死」的雪花发号（upms-admin 同样要上双实例）。
 *
 * <p>{@code IdUtil.getSnowflake(0,0)} 在每个 JVM 里都是机器位 (0,0)；无参 {@code IdUtil.getSnowflake()} /
 * {@code getSnowflakeNextIdStr()} 的机器位由 MAC + PID 推导，容器里 PID 恒为 1。mall-api 上双实例（2026-09-16）后，
 * 两个进程同一毫秒能发出完全相同的号 —— VIP 充值 {@code trade_no} 只有非唯一索引，支付回调按它 {@code getOne}，
 * 重号直接让回调抛异常。
 *
 * <p>统一走 {@code OrderNoGenerator}：机器位由 {@code RegistryWorkerIdAssigner} 按实例认领。
 */
class FixedSnowflakeWorkerGateTest {

	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	private static final Pattern FORBIDDEN = Pattern
		.compile("IdUtil\\s*\\.\\s*(getSnowflake\\s*\\(\\s*\\)|getSnowflake\\s*\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\)|getSnowflakeNextId(Str)?\\s*\\()");

	@Test
	@DisplayName("mall / upms 模块主代码里没有机器位写死的雪花发号")
	void noFixedWorkerSnowflakeInMall() {
		List<Path> sources = Stream.of("zhiflow-mall", "zhiflow-upms")
			.flatMap(m -> mainSources(REPO_ROOT.resolve(m)).stream())
			.toList();
		assertFalse(sources.isEmpty(), "一个源文件都没扫到 —— 判据失效，不是仓库干净");

		List<String> hits = sources.stream().flatMap(FixedSnowflakeWorkerGateTest::hits).toList();

		assertTrue(hits.isEmpty(), () -> "以下调用点机器位写死，双实例会跨进程重号，改注入 OrderNoGenerator：\n  "
				+ String.join("\n  ", hits));
	}

	@Test
	@DisplayName("对照样本：判据能认出三种写法")
	void patternRecognisesAllForms() {
		assertTrue(FORBIDDEN.matcher("IdUtil.getSnowflake(0,0).nextIdStr()").find());
		assertTrue(FORBIDDEN.matcher("IdUtil.getSnowflake(0, 0).nextIdStr()").find());
		assertTrue(FORBIDDEN.matcher("cn.hutool.core.util.IdUtil.getSnowflakeNextIdStr()").find());
		assertTrue(FORBIDDEN.matcher("IdUtil.getSnowflake().nextId()").find());
		assertTrue(FORBIDDEN.matcher("IdUtil.getSnowflake(1, 2).nextIdStr()").find());
		// OrderNoGenerator 自己用的是注入的机器位，不能被误伤
		assertFalse(FORBIDDEN.matcher("IdUtil.getSnowflake(this.workerId, this.dataCenterId)").find());
	}

	private static Stream<String> hits(Path file) {
		try {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			return java.util.stream.IntStream.range(0, lines.size())
				.filter(i -> !isComment(lines.get(i)) && FORBIDDEN.matcher(lines.get(i)).find())
				.mapToObj(i -> REPO_ROOT.relativize(file) + ":" + (i + 1) + "  " + lines.get(i).trim());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** javadoc / 行注释里讲历史的那几行不算调用点。 */
	private static boolean isComment(String line) {
		String t = line.trim();
		return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*");
	}

	private static List<Path> mainSources(Path root) {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> p.toString().contains("/src/main/java/"))
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
