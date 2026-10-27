package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配层为解决「跨服务同接口多实现」而<b>排掉的那一份</b>，必须与留下的那一份等价。
 *
 * <h2>它守的是一个会过期的依据</h2>
 * <p>单体把 8 个服务装进一个进程后冒出一类多进程形态<b>不可能出现</b>的冲突：两个不同名的 bean
 * 只要实现同一个接口，<b>按类型注入</b>时就都是候选 ⇒ {@code NoUniqueBeanDefinitionException}。
 * 实测一处：{@code OrderNoFailureSink} 在 mall-api 与 mall-admin 各有一个实现，类名都叫
 * {@code ErrorLogOrderNoFailureSink}（mall-common 拿不到 {@code ErrorLogRecorder}，
 * 加依赖会把 common-security 拖进它的传递链，所以那边只留接缝、各服务各写一份）。
 *
 * <p>{@code AllAssembly} 在 {@code excludeFilters} 里排掉了 mall-admin 那份。
 * 当时的依据是「两份代码<b>逐字相同</b>，排哪个都一样」—— 这个依据<b>会过期</b>：
 * 将来谁改了其中一份（比如只在 mall-admin 那份里加一段落库前的脱敏），
 * 排除就从「去重」变成<b>静默丢功能</b>：单体照常启动、接口照常返回，
 * 只是那段代码在单体里从来不执行。运行期没有任何信号。
 *
 * <h2>判据：源码归一化后逐字相同</h2>
 * <p>三步归一化：<b>剥注释</b>（javadoc 里写着「本类是 mall-admin 侧的实现」，本来就该不同）、
 * <b>去掉 package 声明</b>（两份必然不同包）、<b>把连续空白压成一个空格</b>（缩进/换行不影响行为）。
 * 剩下的一切 —— 注解、import、字段、方法签名、方法体、字符串字面量 —— 全部参与比对。
 *
 * <p><b>为什么这个判据可靠</b>：它比「行为等价」<b>更严</b>，所以不会漏。
 * 危险的方向只有一个 —— 把真实差异归一化没了；而被归一化掉的三类东西
 * （注释、包名、空白）在语义上<b>不可能</b>改变行为，所以这个方向被堵死了。
 * 反过来它会误红（改个局部变量名也红），那是刻意的：红一次的代价是有人看一眼，
 * 绿错一次的代价是单体里静默少跑一段代码。
 *
 * <p><b>为什么不是「文件字节完全相同」</b>：package 声明与 javadoc 本来就不同，
 * 那条规则从第一天起就是红的 —— 一条从来没绿过的规则等于没有规则。
 *
 * <p><b>为什么不比字节码</b>：class 文件的常量池里嵌着自己的全限定名，两份必然不同；
 * 要比就得先把类名替换掉，那是一套比源码归一化更容易写错的判据。而且本模块
 * <b>刻意不依赖任何业务模块</b>（见 pom 注释），源码比对不需要先编译。
 *
 * <p>剥注释<b>必须是状态机而不是正则</b>：{@code //} 与 {@code /*} 也可能出现在字符串字面量里
 * （本仓 {@code ErrorLogRecorder} 一族里就有 URL 形态的字面量）。
 * 用 {@code //.*$} 一刀切会把两份文件<b>各自</b>截断到同一个位置 —— 真实差异被切没了，
 * 判据于是<b>静默变绿</b>，正是本门禁存在的那种失效模式。
 */
class AssemblyDuplicateImplGateTest {

	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	private static final Path ASSEMBLY_DIR = REPO_ROOT
			.resolve("zhiflow-boot/src/main/java/com/zhiflow/boot/assembly");

	/** {@code Xxx.class} / {@code a.b.Xxx.class} 里的类名。 */
	private static final Pattern CLASS_LITERAL = Pattern
			.compile("([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\.class");

	private static final Pattern PACKAGE_DECLARATION = Pattern.compile("package\\s+[\\w.]+\\s*;");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	/** 类别 B 判定用：{@code @Primary} 注解。判定前一律先 {@code stripComments}。 */
	private static final Pattern PRIMARY_ANNOTATION = Pattern.compile("@Primary\\b");

	/** 类声明里 {@code implements} 后面那串接口名（到左花括号为止）。 */
	private static final Pattern CLASS_IMPLEMENTS = Pattern
			.compile("\\bclass\\s+\\w+[^{]*?\\bimplements\\s+([^{]+)\\{", Pattern.DOTALL);

	private static final Pattern IMPORT_STATEMENT = Pattern
			.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);

	private static final Pattern PACKAGE_NAME = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

	@Test
	@DisplayName("判据自检：装配层至少排掉一个「重复实现」（0 个的话下面那条是假绿）")
	void gateSeesAtLeastOneDuplicateExclusion() throws IOException {
		assertFalse(duplicateExclusions().isEmpty(), """
				装配层的 excludeFilters 里一个非 *Application 的排除都没有了。
				两种可能，处理方式相反：
				  · 重复实现被**真正**消掉了（比如上提到 mall-common、或换成 @Primary 定优先级）
				    —— 那就把本门禁类整个删掉，别把这条自检改成永远通过；
				  · 只是有人顺手删了 excludeFilters / 改了目录名
				    —— 那单体会在启动时抛 NoUniqueBeanDefinitionException，先去看那个。
				无论哪种，「静默通过」都不是选项。""");
	}

	@Test
	@DisplayName("被排掉的重复实现必须与留下的那一份等价（分叉了就不是去重，是静默丢功能）")
	void excludedDuplicatesStayEquivalentToTheOneThatSurvives() throws IOException {
		List<String> offenders = new ArrayList<>();

		for (String excluded : duplicateExclusions()) {
			String simpleName = excluded.substring(excluded.lastIndexOf('.') + 1);
			// 类别 B（同接口多 @Primary）由 primaryConflictExclusionsLeaveExactlyOnePrimary 守，
			// 它天然只有一份实现，不适用「两份逐字相同」这个判据。
			// ⚠️ 这里**不是**豁免：两条测试合起来覆盖全部排除项，
			// 并由 everyExclusionFallsIntoExactlyOneCategory 断言没有第三类漏网。
			if (isPrimaryConflictExclusion(simpleName)) {
				continue;
			}
			List<Path> copies = sourcesNamed(simpleName);

			// 判据失效时宁可炸掉：找不到第二份 = 「两份逐字相同所以排哪个都行」这个前提没了，
			// 而它恰恰是那条 excludeFilters 的**全部**依据。
			assertTrue(copies.size() >= 2, () -> """
					装配层排掉了 %s，但全仓只找到 %d 份同名实现：
					%s
					那条 excludeFilters 的依据是「两份逐字相同，排哪个都一样」——
					只剩一份的话，排除要么已经没有意义（该删），要么排掉的是**唯一**那份
					（= 那个 bean 在单体里根本不存在，按类型注入直接起不来）。
					如果这次排除的不是「跨服务重复实现」而是别的用途，本门禁需要跟着扩展，
					不要把它改成跳过。""".formatted(excluded, copies.size(), String.join("\n",
							copies.stream().map(REPO_ROOT::relativize).map(Path::toString).sorted().toList())));

			Path reference = copies.get(0);
			String referenceForm = normalize(read(reference));
			for (Path other : copies.subList(1, copies.size())) {
				String otherForm = normalize(read(other));
				if (!referenceForm.equals(otherForm)) {
					offenders.add("""
							%s 的两份实现已经分叉：
							  A %s  sha256=%s
							  B %s  sha256=%s
							  首个差异处：
							    A …%s…
							    B …%s…""".formatted(simpleName,
							REPO_ROOT.relativize(reference), digest(referenceForm),
							REPO_ROOT.relativize(other), digest(otherForm),
							excerptAtFirstDifference(referenceForm, otherForm),
							excerptAtFirstDifference(otherForm, referenceForm)));
				}
			}
		}

		assertTrue(offenders.isEmpty(), () -> """
				%s

				AllAssembly 的 excludeFilters 排掉其中一份，唯一的依据就是「两份等价」。
				分叉之后那条排除的语义变成**静默丢功能**：单体照常启动、接口照常返回，
				只是被排掉那份里新加的代码在单体里从来不执行 —— 运行期没有任何信号。

				三条出路，按优先级：
				  1. 把差异合并回两份（继续保持等价）；
				  2. 真有服务间差异 ⇒ 这就不是「重复实现」了，改成按接口分家 / @Primary 显式定优先级，
				     并把 excludeFilters 里那条删掉；
				  3. 最不推荐：确认差异对单体无害并在此登记豁免 —— 但那等于把「等价」换成
				     一次口头承诺，而本门禁存在的原因正是口头承诺会过期。

				归一化只剥掉注释、package 声明与空白；这三类不可能改变行为，
				所以「红了」一定对应一处真实的代码差异。""".formatted(String.join("\n\n", offenders)));
	}

	@Test
	@DisplayName("为「同接口多 @Primary」而排掉的那份，排完必须**恰好剩一个** @Primary")
	void primaryConflictExclusionsLeaveExactlyOnePrimary() throws IOException {
		List<String> offenders = new ArrayList<>();
		Set<String> excludedSimpleNames = new LinkedHashSet<>();
		for (String excluded : duplicateExclusions()) {
			excludedSimpleNames.add(excluded.substring(excluded.lastIndexOf('.') + 1));
		}

		for (String simpleName : excludedSimpleNames) {
			if (!isPrimaryConflictExclusion(simpleName)) {
				continue;
			}
			String itf = implementedInterfaceOf(simpleName);
			List<String> primaries = primaryImplementorsOf(itf);
			List<String> survivors = primaries.stream()
					.filter(fqn -> !excludedSimpleNames.contains(fqn.substring(fqn.lastIndexOf('.') + 1)))
					.toList();

			if (survivors.size() != 1) {
				offenders.add("""
						排掉 %s 之后，接口 %s 在单体里剩下 %d 个 @Primary 实现：
						  %s
						0 个 ⇒ 按类型注入直接 NoUniqueBeanDefinition / NoSuchBean，单体起不来；
						>1 个 ⇒ 冲突根本没解决，只是换了个报错位置。""".formatted(simpleName, itf,
						survivors.size(), survivors.isEmpty() ? "（无）" : String.join("\n  ", survivors)));
			}
		}

		assertTrue(offenders.isEmpty(), () -> """
				%s

				这一类排除的依据与「同名重复实现」不同：不是「两份逐字相同排哪个都行」，
				而是「两份**语义不同**，但被排掉那份保护的失效模式在单体里不存在」。
				典型：mall-api 的 Resilient*Api 兜的是**跨进程调用**在 upms 重启窗口里的失败，
				而单体里 upms 与 mall-api 同一个 JVM、契约调用已走本地实现，没有网络跳。

				所以这条门禁守的不是「等价」，而是**排完之后按类型注入仍然唯一可解**。
				⚠️ 将来若给同一接口再加一个 @Primary 实现，本条会红 —— 那时要做的是
				重新判断哪一份该在单体里生效，不是把新实现也加进 excludeFilters 了事。""".formatted(String.join("\n\n", offenders)));
	}

	@Test
	@DisplayName("判据自检：每个排除项必须**恰好**落进一个类别（否则有排除项无人看守）")
	void everyExclusionFallsIntoExactlyOneCategory() throws IOException {
		List<String> unclassified = new ArrayList<>();
		for (String excluded : duplicateExclusions()) {
			String simpleName = excluded.substring(excluded.lastIndexOf('.') + 1);
			boolean categoryB = isPrimaryConflictExclusion(simpleName);
			boolean categoryA = !categoryB && sourcesNamed(simpleName).size() >= 2;
			if (!categoryA && !categoryB) {
				unclassified.add(excluded);
			}
		}
		assertTrue(unclassified.isEmpty(), () -> """
				这些排除项两个类别都不属于，等于**没有任何门禁在看**：
				  %s
				类别 A = 同名重复实现（全仓 >=2 份同名主源码）；
				类别 B = 同接口多 @Primary（该类标了 @Primary，且同接口另有 @Primary 实现）。
				新增第三类用途时**必须同时新增守它的断言**，否则排除的语义无人可查 ——
				而排错的后果（静默丢功能 / 单体起不来）恰恰都不会在别处报出来。""".formatted(String.join("\n  ", unclassified)));
	}

	// ------------------------------------------------------------ 类别 B 判定

	/** 该排除项是否属于「同接口多 @Primary」：自己标了 @Primary，且同接口另有 @Primary 实现。 */
	private static boolean isPrimaryConflictExclusion(String simpleName) throws IOException {
		List<Path> sources = sourcesNamed(simpleName);
		if (sources.size() != 1) {
			return false;
		}
		if (!hasPrimary(read(sources.get(0)))) {
			return false;
		}
		String itf = implementedInterfaceOf(simpleName);
		return itf != null && primaryImplementorsOf(itf).size() >= 2;
	}

	private static boolean hasPrimary(String source) {
		return PRIMARY_ANNOTATION.matcher(JavaSource.stripComments(source)).find();
	}

	/** 该类 {@code implements} 的第一个接口，解析成<b>全限定名</b>。 */
	private static String implementedInterfaceOf(String simpleName) throws IOException {
		List<Path> sources = sourcesNamed(simpleName);
		if (sources.size() != 1) {
			return null;
		}
		return firstImplementedInterfaceFqn(read(sources.get(0)));
	}

	/**
	 * ⚠️ 必须解析成<b>全限定名</b>再比。按简单名比会把
	 * {@code mall.admin.service.XxxService} 与 {@code mall.api.service.XxxService}
	 * 当成同一个接口 —— 它们是两个不同接口、各自只有一个实现，根本不冲突。
	 * （2026-08-21 写这条门禁的配套扫描时正是这么踩的，100+ 条全是假警报。）
	 */
	private static String firstImplementedInterfaceFqn(String source) {
		String code = JavaSource.stripComments(source);
		Matcher decl = CLASS_IMPLEMENTS.matcher(code);
		if (!decl.find()) {
			return null;
		}
		String first = decl.group(1).split(",")[0].split("<")[0].trim();
		String simple = first.substring(first.lastIndexOf('.') + 1);
		Matcher imports = IMPORT_STATEMENT.matcher(code);
		while (imports.find()) {
			String fqn = imports.group(1);
			if (fqn.substring(fqn.lastIndexOf('.') + 1).equals(simple)) {
				return fqn;
			}
		}
		Matcher pkg = PACKAGE_NAME.matcher(code);
		return pkg.find() ? pkg.group(1) + "." + simple : simple;
	}

	/** 全仓主源码里 {@code implements} 该接口且标了 {@code @Primary} 的类（全限定名）。 */
	private static List<String> primaryImplementorsOf(String interfaceFqn) throws IOException {
		List<String> found = new ArrayList<>();
		try (Stream<Path> files = Files.walk(REPO_ROOT)) {
			for (Path p : files.filter(f -> f.toString().endsWith(".java"))
					.filter(f -> f.toString().contains("/src/main/java/"))
					.filter(f -> !f.toString().contains("/target/"))
					// worktree 里是整个仓库的副本，算进来会把同一个类数两遍
					.filter(f -> !f.toString().contains("/.claude/worktrees/"))
					.sorted().toList()) {
				String source = read(p);
				if (!hasPrimary(source)) {
					continue;
				}
				if (interfaceFqn.equals(firstImplementedInterfaceFqn(source))) {
					Matcher pkg = PACKAGE_NAME.matcher(JavaSource.stripComments(source));
					String simple = p.getFileName().toString().replace(".java", "");
					found.add(pkg.find() ? pkg.group(1) + "." + simple : simple);
				}
			}
		}
		return found;
	}

	// ------------------------------------------------------------ 装配层解析

	/**
	 * 装配层 {@code excludeFilters} 里<b>非 {@code *Application}</b> 的排除项。
	 *
	 * <p>{@code *Application} 那批是另一回事（profile 隔离，见 {@code BootAssemblyGateTest}），
	 * 它们本来就只有一份。剩下的排除项在本仓只有一个来源：跨服务同接口重复实现。
	 */
	private static Set<String> duplicateExclusions() throws IOException {
		Set<String> names = new LinkedHashSet<>();
		for (Path assembly : assemblies()) {
			// 必须先剥注释：装配类的 javadoc 里就写着 {@code excludeFilters}，
			// 直接 indexOf 会定位到那一处、于是往后取到的是 javadoc 里的括号块。
			// 2026-08-16 实测 BootAssemblyGateTest 正是这么静默漏掉 AllAssembly 的（已一并修）。
			String source = JavaSource.stripComments(read(assembly));
			int at = source.indexOf("excludeFilters");
			if (at < 0) {
				continue;
			}
			Matcher matcher = CLASS_LITERAL.matcher(balancedBraces(source, source.indexOf('{', at)));
			while (matcher.find()) {
				String name = matcher.group(1);
				if (!name.endsWith("Application")) {
					names.add(name);
				}
			}
		}
		return names;
	}

	/**
	 * 从 {@code open} 处的左花括号取一对<b>平衡</b>花括号里的内容。
	 *
	 * <p>不能用「找第一个右花括号」：{@code excludeFilters = { @Filter(classes = { ... }), ... }}
	 * 内层还有一层 —— 截到第一个右花括号就只拿到第一个 filter，第二个（正是重复实现那条）
	 * 会被<b>整条漏掉</b>，规则于是对分叉的代码静默放行。
	 *
	 * <p>也不能改成取 {@code @ComponentScan(} 的平衡圆括号：那个范围里还有
	 * {@code nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class}，
	 * 会被当成一条「被排除的重复实现」，于是门禁去全仓找它的同名双胞胎、找不到、误红。
	 */
	private static String balancedBraces(String source, int open) {
		if (open < 0) {
			return "";
		}
		int depth = 0;
		for (int i = open; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '{') {
				depth++;
			}
			else if (c == '}') {
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
			return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}

	/**
	 * 全仓按简单类名找<b>全部</b>主源码（不是第一个 —— 找的就是重名的那几份）。
	 *
	 * <p>⚠️ 必须排掉 {@code .claude/worktrees/}：那里面是<b>整个仓库的副本</b>（而且往往是
	 * 别的分支）。不排的话任何一个类都会凭空多出「另一份实现」，本门禁于是去比对两个
	 * <b>不同分支</b>的同名文件、报「已经分叉」—— 2026-08-21 实测正是这样：
	 * {@code SimpleRedisLock} 被算成两份，A 来自某个 agent worktree、B 是真身，判定分叉。
	 * 后果不只是误红：它让门禁的结论<b>取决于磁盘上碰巧存在哪些 worktree</b>，
	 * 同一份代码在两台机器上能得出相反结论。
	 */
	private static List<Path> sourcesNamed(String simpleName) throws IOException {
		try (Stream<Path> files = Files.walk(REPO_ROOT)) {
			return files.filter(p -> p.getFileName().toString().equals(simpleName + ".java"))
					.filter(p -> p.toString().contains("/src/main/java/"))
					.filter(p -> !p.toString().contains("/target/"))
					.filter(p -> !p.toString().contains("/.claude/worktrees/"))
					.sorted()
					.toList();
		}
	}

	// ------------------------------------------------------------ 归一化

	/** 剥注释 → 去 package 声明 → 空白压成一个空格。剩下的一切都参与比对。 */
	private static String normalize(String source) {
		String code = JavaSource.stripComments(source);
		String withoutPackage = PACKAGE_DECLARATION.matcher(code).replaceFirst(" ");
		return WHITESPACE.matcher(withoutPackage).replaceAll(" ").trim();
	}

	/** 归一化形态的指纹，只为了让失败信息能一眼看出「是不是同一份」。判据本身是逐字相等。 */
	private static String digest(String normalized) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < 8; i++) {
				hex.append("%02x".formatted(hash[i]));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("JVM 没有 SHA-256", e);
		}
	}

	/** 第一处不同的上下文，让失败信息能直接定位到差异而不是甩两个哈希。 */
	private static String excerptAtFirstDifference(String self, String other) {
		int at = 0;
		while (at < self.length() && at < other.length() && self.charAt(at) == other.charAt(at)) {
			at++;
		}
		int from = Math.max(0, at - 60);
		int to = Math.min(self.length(), at + 60);
		return self.substring(from, to);
	}

	private static String read(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
	}
}
