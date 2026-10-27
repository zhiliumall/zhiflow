package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.beans.Introspector;
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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * bean 名全限定化的守卫（v3 P2.1-A）。
 *
 * <h2>它在防什么</h2>
 * <p>Spring 默认的 {@code AnnotationBeanNameGenerator} 用「类简单名首字母小写」当 bean 名。
 * 本仓有 <b>245 组</b>跨服务同简单名的组件类（2026-08-15 实扫，绝大多数是
 * {@code mall-api} ⇄ {@code mall-admin} 的同名 Controller / ServiceImpl），
 * 阶段 3 把它们装配进同一进程时会互相覆盖，谁生效取决于扫描顺序。
 * 所以单体装配类的 component-scan 与 {@code @MapperScan} 都挂了
 * {@code FullyQualifiedAnnotationBeanNameGenerator}。
 *
 * <p><b>改完之后真正的风险不是编译，是「按名字找 bean」的地方。</b>
 * 名字这种依赖没有类型系统兜底：引用方拿一个字符串去找，找不到就在<b>运行期</b>炸，
 * 而且往往炸在没人看的路径上（定时任务、异步消费者）。本测试把这类引用变成编译期可见。
 *
 * <h2>两条规则</h2>
 * <ol>
 *   <li><b>单体装配类挂上了生成器</b>（{@code @ComponentScan} 与 {@code @MapperScan}），
 *       由 {@link BootAssemblyGateTest} 守。</li>
 *   <li><b>没有任何按名注入点指向「被扫描类的派生简单名」</b>。
 *       {@code @Bean} 方法名不受生成器影响，所以引用 {@code tenantRequestInterceptor}、
 *       {@code securityMessageSource} 这类没问题；出问题的只有
 *       「引用一个 {@code @Component} 类的默认名」。</li>
 * </ol>
 *
 * <p><b>为什么读源文件而不是 ArchUnit</b>：与 {@link DuplicateNameGateTest} 同因——
 * 注解的字符串实参与「有没有写 nameGenerator」在字节码里也能读，
 * 但同一份规则要同时判源文件里的注释形态（见下），读源码更直接、也更好排错。
 *
 * <p><b>注释必须先剥掉。</b>本仓的 javadoc 里大量出现 {@code @Qualifier("consumerListener")}
 * 这类**举例**（正是在解释本次改动），不剥注释的话门禁会对着自己的说明文字报警——
 * 那种规则第一天就会被加豁免绕过。剥离用的是带字符串/字符字面量状态的扫描，
 * 不是 {@code replaceAll}：{@code "http://x"} 里的 {@code //} 不是注释起点。
 *
 * <p><b>fail-closed</b>：扫不到源文件一律失败（与 {@link ArchScope} 同一条原则）。
 */
class BeanNameGateTest {

	/** 与 {@link DuplicateNameGateTest} 同：测试的工作目录是本模块目录。 */
	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	private static final Set<String> SKIP_DIRS = Set.of("target", "node_modules", "dist", "logs");

	/** 装进单体的业务模块（与 {@code AllAssembly} 的扫描面一致）。 */
	private static final List<String> IN_SCOPE_MODULES = List.of(
			"zhiflow-mall/zhiflow-mall-api", "zhiflow-mall/zhiflow-mall-admin",
			"zhiflow-upms/zhiflow-upms-admin", "zhiflow-auth",
			"zhiflow-weixin/zhiflow-weixin-admin", "zhiflow-pay/zhiflow-pay-admin");

	/** 会产生 bean 的类级注解；捕获组 2 是显式 bean 名（写了就不受生成器影响）。 */
	private static final Pattern STEREOTYPE = Pattern.compile(
			"(?m)^\\s*@(Component|Service|RestController|Controller|Repository|Configuration)"
					+ "\\s*(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"\\s*\\))?");

	/** 按名找 bean 的五种写法。捕获组 1 一律是被引用的 bean 名。 */
	private static final List<Pattern> BY_NAME_REFERENCES = List.of(
			Pattern.compile("@Qualifier\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\""),
			Pattern.compile("@Resource\\(\\s*name\\s*=\\s*\"([^\"]+)\""),
			Pattern.compile("@Async\\(\\s*\"([^\"]+)\""),
			Pattern.compile("@DependsOn\\(\\s*\\{?\\s*\"([^\"]+)\""),
			Pattern.compile("getBean(?:sOfType)?\\w*\\(\\s*\"([^\"]+)\""),
			Pattern.compile("beansOfType\\.get\\(\\s*\"([^\"]+)\""));

	private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

	// ------------------------------------------------------------------ 规则 2

	@Test
	@DisplayName("按名注入点不得指向被扫描类的派生简单名（那些名字已经不存在了）")
	void noByNameReferenceToGeneratedSimpleBeanName() throws IOException {
		Map<String, Path> derivedNames = new TreeMap<>();   // 派生简单名 -> 定义它的类
		Set<String> pinnedNames = new LinkedHashSet<>();    // 显式写死的 bean 名，不受生成器影响

		for (Path java : javaSources()) {
			if (!isInScopeService(java)) {
				continue;
			}
			String source = stripComments(read(java));
			Matcher stereotype = STEREOTYPE.matcher(source);
			boolean isBean = false;
			while (stereotype.find()) {
				isBean = true;
				if (stereotype.group(2) != null) {
					pinnedNames.add(stereotype.group(2));
					isBean = false;   // 显式命名：生成器不改它
				}
			}
			if (isBean) {
				derivedNames.put(Introspector.decapitalize(simpleNameOf(java)), java);
			}
		}

		// 2026-10-20 开源分支删掉大批功能后实测 185 个
		assertTrue(derivedNames.size() > 150,
				"只扫到 " + derivedNames.size() + " 个按默认规则命名的组件类，远低于本仓规模——"
						+ "扫描路径或工作目录不对。门禁宁可炸掉也不能静默放行。");

		List<String> broken = new ArrayList<>();
		for (Path java : javaSources()) {
			String source = stripComments(read(java));
			for (Pattern pattern : BY_NAME_REFERENCES) {
				Matcher reference = pattern.matcher(source);
				while (reference.find()) {
					String name = reference.group(1);
					if (pinnedNames.contains(name) || !derivedNames.containsKey(name)) {
						continue;
					}
					broken.add("  \"%s\"%n      引用方 : %s%n      被引用 : %s"
							.formatted(name, REPO_ROOT.relativize(java),
									REPO_ROOT.relativize(derivedNames.get(name))));
				}
			}
		}

		if (!broken.isEmpty()) {
			fail("""
					发现 %d 处按名注入点指向「被扫描类的派生简单名」。这些服务已挂
					FullyQualifiedAnnotationBeanNameGenerator，那个简单名在容器里不存在了 ——
					启动时 NoSuchBeanDefinitionException，或者更糟：只在某条冷路径上才触发。

					两种改法，优先第一种：
					  1. 改成按**类型**注入（多实现时用具体类而不是接口）——从此不依赖名字；
					  2. 确实需要名字时，在被引用的类上显式写 @Component("那个名字") 钉死，
					     并在 javadoc 说明谁在按这个名字找它。
					%s""".formatted(broken.size(), String.join(System.lineSeparator(), broken)));
		}
	}

	// ------------------------------------------------------------------ 工具

	/**
	 * 去掉行注释与块注释，保留字符串/字符字面量里的内容。
	 *
	 * <p>用状态机而不是正则：{@code "http://x"} 里的 {@code //} 不是注释起点，
	 * 而本仓的 javadoc 里到处是 {@code @Qualifier("…")} 这类举例，
	 * 不剥干净门禁就会对着自己的说明文字报警。
	 */
	private static String stripComments(String source) {
		StringBuilder out = new StringBuilder(source.length());
		int index = 0;
		while (index < source.length()) {
			char current = source.charAt(index);
			char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

			if (current == '/' && next == '/') {
				while (index < source.length() && source.charAt(index) != '\n') {
					index++;
				}
			}
			else if (current == '/' && next == '*') {
				index += 2;
				while (index + 1 < source.length()
						&& !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) {
					// 注释里的换行要保留，否则行号对不上、报错难查
					if (source.charAt(index) == '\n') {
						out.append('\n');
					}
					index++;
				}
				index = Math.min(index + 2, source.length());
			}
			else if (current == '"' || current == '\'') {
				char quote = current;
				out.append(current);
				index++;
				while (index < source.length()) {
					char inside = source.charAt(index);
					out.append(inside);
					index++;
					if (inside == '\\' && index < source.length()) {
						out.append(source.charAt(index));
						index++;
					}
					else if (inside == quote || inside == '\n') {
						break;
					}
				}
			}
			else {
				out.append(current);
				index++;
			}
		}
		return out.toString();
	}

	private static boolean isInScopeService(Path java) {
		String normalized = normalize(REPO_ROOT.relativize(java));
		return IN_SCOPE_MODULES.stream().anyMatch(module -> normalized.startsWith(module + "/"));
	}

	private static List<Path> javaSources() throws IOException {
		try (Stream<Path> walk = Files.walk(REPO_ROOT)) {
			return walk
					.filter(path -> !isSkipped(path))
					.filter(Files::isRegularFile)
					.filter(path -> normalize(path).contains("/src/main/java/"))
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.toList();
		}
	}

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

	private static String simpleNameOf(Path javaFile) {
		String name = javaFile.getFileName().toString();
		return name.substring(0, name.length() - ".java".length());
	}

	private static String fqcnOf(Path javaFile, String source) {
		Matcher matcher = PACKAGE.matcher(source);
		return matcher.find() ? matcher.group(1) + "." + simpleNameOf(javaFile) : simpleNameOf(javaFile);
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
