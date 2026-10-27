package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 每份 {@code application*.yml} 都必须能被<b>真的 YAML 解析器</b>解析，且<b>不许有重复键</b>。
 *
 * <h2>为什么要这条</h2>
 * <p>2026-09-13（B1）给 im 的 application.yml 加 gRPC 配置时，我在文件中段另起了一个顶层
 * {@code spring:} —— 而文件开头已经有一个。YAML 规范禁止重复键，Boot 启动直接抛：
 *
 * <pre>
 * found duplicate key spring
 *  in 'reader', line 97, column 1
 * </pre>
 *
 * <p><b>本地全绿，是真部署才炸的。</b>为什么本地看不见：
 * <ul>
 * <li>{@code GrpcServerAssemblySwitchTest} 用的是自写的缩进解析器（为了读「键路径→值」），
 * 它<b>容忍</b>重复键 —— 后一个覆盖前一个，断言照样通过；</li>
 * <li>各服务单测用 {@code src/test/resources} 下的 yml，或者压根不启动完整上下文。</li>
 * </ul>
 *
 * <p>与本仓记过的「{@code @Select} 的 {@code <script>} 里写裸 {@code <>} 让应用启动即崩而单测全绿」
 * 是同一个形状：<b>配置/资源文件的语法不在任何判据的视野里</b>。本门禁补这个视野 ——
 * 用 Boot 内部同一套 snakeyaml 把每份 yml 真解析一遍。
 */
class ApplicationYamlParsesGateTest {

	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	@TestFactory
	@DisplayName("每份 application*.yml 都能被 snakeyaml 解析（重复键会像运行时一样抛）")
	Stream<DynamicTest> everyApplicationYamlParses() {
		List<Path> files = ymlFiles();
		assertFalse(files.isEmpty(), "一份 application yml 都没扫到 —— 判据失效，不是仓库干净");
		return files.stream().map(f -> DynamicTest.dynamicTest(REPO_ROOT.relativize(f).toString(), () -> {
			try {
				parse(Files.readString(f, StandardCharsets.UTF_8));
			}
			catch (RuntimeException ex) {
				fail("""
						%s 解析失败：%s

						最常见的原因是**重复的顶层键**（例如文件里出现了两个 `spring:`）——
						往一份 yml 中间或末尾追加配置时最容易犯。YAML 规范禁止重复键，
						Boot 启动会直接抛 `found duplicate key`，而单测与自写的缩进解析器都看不出来。
						""".formatted(REPO_ROOT.relativize(f), ex.getMessage()));
			}
		}));
	}

	@Test
	@DisplayName("自检：重复顶层键真的会被这套解析器拒（否则上面那条恒绿）")
	void duplicateKeysAreRejected() {
		String broken = """
				spring:
				  application:
				    name: x
				server:
				  port: 1
				spring:
				  grpc:
				    server:
				      enabled: false
				""";
		assertThrows(RuntimeException.class, () -> parse(broken), """
				重复的顶层 spring 键竟然解析通过了 —— 这条门禁什么都没在守。
				snakeyaml 默认 allowDuplicateKeys=true，必须显式关掉
				（Boot 的 OriginTrackedYamlLoader 也是关的，这里要与运行时一致）。""");
		parse("spring:\n  application:\n    name: x\n");
	}

	@Test
	@DisplayName("自检：扫描面覆盖到主要服务的 yml（漏扫 = 假绿）")
	void theScanCoversTheRealFiles() {
		List<String> names = ymlFiles().stream().map(f -> REPO_ROOT.relativize(f).toString()).toList();
		for (String must : List.of("zhiflow-boot/src/main/resources/application.yml",
				"zhiflow-boot/src/test/resources/application-smoke.yml")) {
			assertTrue(names.contains(must), () -> "扫描面里没有 " + must + "，实际扫到：" + names);
		}
	}

	/**
	 * Maven 资源过滤占位符：{@code name: @artifactId@}。
	 *
	 * <p>源文件里它<b>不是</b>合法 YAML（{@code @} 不能起一个 token），
	 * 但构建时会被替换成真值，落到 {@code target/classes} 的那份是合法的。
	 * 所以解析前先替掉 —— 判据要盯的是**人写的那部分**的语法，
	 * 不是去复述 Maven 的过滤行为。
	 *
	 * <p>替换刻意只做这一种最小形状（{@code @标识符@}），不做通用清洗：
	 * 清洗做宽了就会把真错也洗掉。
	 */
	private static final java.util.regex.Pattern MAVEN_PLACEHOLDER = java.util.regex.Pattern
			.compile("@[A-Za-z][A-Za-z0-9_.-]*@");

	private static void parse(String text) {
		text = MAVEN_PLACEHOLDER.matcher(text).replaceAll("maven-filtered-value");
		LoaderOptions options = new LoaderOptions();
		// ⚠️ 默认是 true。不关掉重复键会被静默合并，本门禁就成了摆设。
		options.setAllowDuplicateKeys(false);
		new Yaml(new SafeConstructor(options)).loadAll(text).forEach(doc -> {
		});
	}

	private static List<Path> ymlFiles() {
		try (Stream<Path> walk = Files.walk(REPO_ROOT)) {
			List<Path> out = new ArrayList<>(walk.filter(f -> {
				String name = f.getFileName().toString();
				return name.startsWith("application") && (name.endsWith(".yml") || name.endsWith(".yaml"));
			}).filter(f -> !f.toString().contains("/target/")).filter(f -> !f.toString().contains("/.claude/"))
					.filter(f -> f.toString().contains("/src/")).toList());
			out.sort(Path::compareTo);
			return out;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
