package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 每个 {@code @Aspect} 都必须真的被装配成 bean。
 *
 * <p><b>存在理由（2026-08-15 实测到的真实事故）</b>：{@code BaseSecurityInsideAspect} 写着
 * 完整的「服务间接口必须带 {@code from: Y} 头」校验逻辑，却<b>从未被装配</b> ——
 * {@code @Aspect} 不是 Spring 的 stereotype 注解，光有它不会被 component-scan 拾取，
 * 而该类既没有 {@code @Component}，也不在任何 {@code AutoConfiguration.imports} 里，
 * 全仓也没有 {@code @Bean} 方法造它。
 *
 * <p>后果是一整条安全控制静默不存在：{@code PermitAllUrlProperties} 把带 {@code @Inside}
 * 的端点无条件放进 permitAll 白名单（Spring Security 层不要 token），
 * 本该在其后补上的 {@code from} 头校验从来没跑过，于是全部 {@code @Inside(value=true)}
 * 端点对公网无鉴权可达 —— 实测未认证 {@code GET /upms/tenant/inside/list} 返回 200 与全量租户列表。
 *
 * <p><b>为什么这一类缺陷必须用门禁而不是评审来防</b>：它<b>编译通过、单测也通过</b>。
 * 切面的单测（如 {@code SysLogAspectTest}）都是直接 {@code new} 出实例再调方法，
 * 根本不经过 Spring 容器，因此「这个切面到底有没有被注册」这个问题，
 * 在任何一层单测里都不成立。只有在生产上发一个不带凭证的请求才看得出来 ——
 * 而那正是这条规则要替我们做的事。
 *
 * <p>判据是「有没有任何一条装配路径」，三选一即可：
 * <ol>
 *   <li>类上有 {@code @Component}（或其派生 stereotype）——如 {@code DistributedLockAspect}；</li>
 *   <li>某个 {@code @Bean} 方法返回它——如 {@code LogAutoConfiguration#sysLogAspect}；</li>
 *   <li>它自己出现在某个 {@code AutoConfiguration.imports} 里。</li>
 * </ol>
 *
 * <p>刻意<b>不</b>检查「装配路径是否真的对这个服务生效」（比如 component-scan 的 basePackages
 * 覆没覆盖到）——那需要起容器才能判定。本规则只拦「一条路径都没有」这种确定错误，
 * 宁可漏报也不误报。
 */
class AspectRegistrationGateTest {

	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	private static final Set<String> SKIP_DIRS = Set.of("target", "node_modules", "dist", "logs");

	private static final Pattern ASPECT_CLASS = Pattern.compile(
			"@Aspect\\b[\\s\\S]{0,400}?\\b(?:public\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");

	/** 类级 stereotype：@Component 及其派生。 */
	private static final Pattern STEREOTYPE = Pattern.compile(
			"@(Component|Service|Repository|Controller|RestController|Configuration)\\b");

	/** 抽象切面基类由子类装配，自身不该是 bean。 */
	private static final Pattern ABSTRACT_CLASS = Pattern.compile("abstract\\s+class\\s+");

	@Test
	@DisplayName("每个 @Aspect 都有装配路径（@Component / @Bean / AutoConfiguration.imports）")
	void everyAspectIsRegistered() throws IOException {
		List<Path> javaFiles = filesUnder("src/main/java", ".java");
		// 2026-10-20 开源分支删掉大批功能后实测 995 个
		assertTrue(javaFiles.size() > 800,
				"只扫到 " + javaFiles.size() + " 个主源码文件，扫描路径不对。门禁宁可炸掉也不能静默放行。");

		String allSources = concatAll(javaFiles);
		String allImports = concatAll(filesUnder("src/main/resources/META-INF/spring", ".imports"));

		List<String> unregistered = new ArrayList<>();
		int aspects = 0;
		for (Path file : javaFiles) {
			String source = read(file);
			if (!source.contains("@Aspect")) {
				continue;
			}
			Matcher matcher = ASPECT_CLASS.matcher(source);
			if (!matcher.find()) {
				continue;
			}
			String simpleName = matcher.group(1);
			if (ABSTRACT_CLASS.matcher(matcher.group()).find()) {
				continue;
			}
			aspects++;

			String declaration = matcher.group();
			boolean hasStereotype = STEREOTYPE.matcher(declaration).find();
			boolean hasBeanMethod = Pattern
					.compile("@Bean[\\s\\S]{0,300}?\\b" + Pattern.quote(simpleName) + "\\s+\\w+\\s*\\(")
					.matcher(allSources).find();
			boolean inImports = allImports.contains(simpleName);

			if (!hasStereotype && !hasBeanMethod && !inImports) {
				unregistered.add("  %s%n      %s".formatted(
						simpleName, REPO_ROOT.relativize(file)));
			}
		}

		assertTrue(aspects >= 5, "只识别到 " + aspects + " 个 @Aspect，判据可能失效了。");

		if (!unregistered.isEmpty()) {
			fail("""
					发现 %d 个 @Aspect 没有任何装配路径 —— 它们的 advice 一次都不会执行。
					@Aspect 不是 stereotype 注解，光写它不会被 component-scan 拾取。
					三选一补上：类上加 @Component、写一个返回它的 @Bean 方法、
					或把它登记进 META-INF/spring 的 AutoConfiguration.imports。
					%s""".formatted(unregistered.size(), String.join(System.lineSeparator(), unregistered)));
		}
	}

	private static String concatAll(List<Path> files) {
		StringBuilder builder = new StringBuilder();
		for (Path file : files) {
			builder.append(read(file)).append('\n');
		}
		return builder.toString();
	}

	private static List<Path> filesUnder(String sourceRoot, String suffix) throws IOException {
		String marker = "/" + sourceRoot + "/";
		try (Stream<Path> walk = Files.walk(REPO_ROOT)) {
			return walk
					.filter(path -> !isSkipped(path))
					.filter(Files::isRegularFile)
					.filter(path -> path.toString().replace('\\', '/').contains(marker))
					.filter(path -> path.getFileName().toString().endsWith(suffix))
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

	private static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			try {
				return new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
			}
			catch (IOException fallback) {
				return "";
			}
		}
	}
}
