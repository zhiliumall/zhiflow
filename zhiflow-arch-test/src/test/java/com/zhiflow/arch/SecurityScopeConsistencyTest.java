package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * [P2.3-B] 安全链两个口径的一致性门禁。
 *
 * <h2>守的是什么</h2>
 * <p>P2.3-B 把「本服务的端点由哪套认证机制管辖」从共享库里的
 * {@code "zhiflow-mall-api".equals(spring.application.name)} 换成了服务自己声明的
 * {@code zhiflow.security.authentication-mode}。这个改法把一类静默故障
 * （改名 / 拆服务 / 阶段 3 合并进单体时安全姿态无声翻转）换成了另一类：
 * <b>漏声明</b>。所以漏声明必须在构建期就炸，而不是等到生产上 C 端全线 401。
 *
 * <p>三条规则：
 * <ol>
 * <li>单体配置里 C 端门户的命名空间（{@code /mallapi}，mall-api 的 controller 包）必须声明
 *     {@code authentication-mode: api-login} —— 它是唯一走 {@code @ApiLogin} + {@code ThirdSessionInterceptor} 的模块，
 *     漏了就退回 oauth2 模式：整个 C 端要 Bearer token，且 {@code PORTAL_PUBLIC_URLS}
 *     （含各支付回调）不再装载；</li>
 * <li>别的命名空间<b>不许</b>声明 {@code api-login}，顶层也不许 —— 那等于把对应模块在 Security 层全开；</li>
 * <li>{@code SecurityScope.PATH_PREFIX_PROPERTY} 与 {@code PathPrefixProperties} 的
 *     {@code @ConfigurationProperties(prefix)} 必须指向同一个键。两个模块各写了一份字符串
 *     （common-security 不依赖 common-data），写岔了的表现是
 *     「MVC 加了前缀、安全链却还圈着老范围」—— 正是 2026-08-15 auth 那次 12 个端点变 302 的形态。</li>
 * </ol>
 */
class SecurityScopeConsistencyTest {

	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	private static final String MODE_KEY = "zhiflow.security.authentication-mode";

	private static final String API_LOGIN = "api-login";

	private static final String MVC_PREFIX_KEY = "zhiflow.path-prefix.value";

	private static final String SECURITY_PREFIX_KEY = "zhiflow.security.path-prefix";

	private static final String BOOT_YML = "zhiflow-boot/src/main/resources/application.yml";

	private static final String PORTAL_NAMESPACE = "/mallapi";

	private static final String PORTAL_BASE_PACKAGE = "com.zhiflow.mall.api.controller";

	/** 只认「缩进 + 键名 + 冒号」这种映射行。列表项、纯值行、注释、空行一律跳过。 */
	private static final Pattern KEY_LINE = Pattern.compile("^(\\s*)([\\w.\\-]+):(\\s*.*)$");

	@Test
	@DisplayName("C 端门户命名空间必须声明 authentication-mode: api-login（漏了 = C 端全线 401）")
	void portalNamespaceDeclaresApiLoginMode() throws IOException {
		Map<String, Object> portal = namespaces().stream()
			.filter(ns -> PORTAL_NAMESPACE.equals(ns.get("value")))
			.findFirst()
			.orElse(null);
		assertTrue(portal != null, () -> BOOT_YML + " 的 zhiflow.path-prefix.namespaces 里没有 " + PORTAL_NAMESPACE
				+ " —— 判据本身失效，或命名空间改名了（改了就同步本测试）");
		assertEquals(PORTAL_BASE_PACKAGE, portal.get("base-package"));
		assertEquals(API_LOGIN, portal.get("authentication-mode"), () -> """
				%s 命名空间没有声明 authentication-mode: %s。
				没有它，common-security 会按默认的 oauth2 模式装配：
				C 端全部端点要 Bearer token，且 PORTAL_PUBLIC_URLS（含支付回调）不再进白名单。""".formatted(
						PORTAL_NAMESPACE, API_LOGIN));
	}

	@Test
	@DisplayName("只有 C 端门户能声明 api-login —— 别的命名空间或顶层声明了等于 Security 层全开")
	void noOtherNamespaceClaimsApiLoginMode() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Map<String, Object> ns : namespaces()) {
			if (!PORTAL_NAMESPACE.equals(ns.get("value")) && API_LOGIN.equals(ns.get("authentication-mode"))) {
				offenders.add(String.valueOf(ns.get("value")));
			}
		}
		for (Path yml : serviceApplicationYmls()) {
			if (API_LOGIN.equals(flatten(yml).get(MODE_KEY))) {
				offenders.add(REPO_ROOT.relativize(yml) + " 顶层 " + MODE_KEY);
			}
		}
		assertTrue(offenders.isEmpty(), () -> """
				这些地方声明了 authentication-mode: %s —— 该模式让 Spring Security 对对应命名空间全放行，
				只有靠 @ApiLogin + ThirdSessionInterceptor 在 MVC 层认证的 C 端门户才能用：
				%s""".formatted(API_LOGIN, String.join("\n", offenders)));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> namespaces() throws IOException {
		Path yml = REPO_ROOT.resolve(BOOT_YML);
		assertTrue(Files.isRegularFile(yml), "找不到单体配置：" + yml);
		Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions()))
			.load(Files.readString(yml, StandardCharsets.UTF_8));
		Object list = path(root, "zhiflow", "path-prefix", "namespaces");
		assertTrue(list instanceof List<?> l && !l.isEmpty(),
				"zhiflow.path-prefix.namespaces 为空 —— 判据本身失效，下面的断言会静默通过");
		return (List<Map<String, Object>>) list;
	}

	@SuppressWarnings("unchecked")
	private static Object path(Map<String, Object> map, String... keys) {
		Object cur = map;
		for (String key : keys) {
			if (!(cur instanceof Map<?, ?> m)) {
				return null;
			}
			cur = ((Map<String, Object>) m).get(key);
		}
		return cur;
	}

	@Test
	@DisplayName("一个服务不许同时声明两个前缀键（安全链覆盖项存在就说明它不走 addPathPrefix）")
	void noServiceDeclaresBothPrefixKeys() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path yml : serviceApplicationYmls()) {
			Map<String, String> flat = flatten(yml);
			if (flat.containsKey(SECURITY_PREFIX_KEY) && flat.containsKey(MVC_PREFIX_KEY)) {
				offenders.add(REPO_ROOT.relativize(yml).toString());
			}
		}
		assertTrue(offenders.isEmpty(), () -> """
				这些服务同时声明了 %s 与 %s：
				%s
				覆盖项的存在意义就是「本服务的命名空间不是 addPathPrefix 加出来的」（im-server 的
				/api 写在 controller 源码里）。两个都配意味着有人误以为要成对出现 ——
				真配上 path-prefix.value 的话，addPathPrefix 会再加一层，变成 /api/api/im/**。""".formatted(
						SECURITY_PREFIX_KEY, MVC_PREFIX_KEY, String.join("\n", offenders)));
	}

	@Test
	@DisplayName("前缀属性名只在 common-core 写一份，且与绑定宿主一致")
	void prefixPropertyNameHasSingleSource() throws IOException {
		Path constants = REPO_ROOT.resolve(
				"zhiflow-common/zhiflow-common-core/src/main/java/com/zhiflow/"
						+ "common/core/constant/PathPrefixConstants.java");
		Path properties = REPO_ROOT.resolve(
				"zhiflow-common/zhiflow-common-data/src/main/java/com/zhiflow/"
						+ "common/data/web/PathPrefixProperties.java");
		assertTrue(Files.isRegularFile(constants), "找不到 PathPrefixConstants.java：" + constants);
		assertTrue(Files.isRegularFile(properties), "找不到 PathPrefixProperties.java：" + properties);

		String declared = firstGroup(constants, Pattern.compile("PROPERTY\\s*=\\s*\"([^\"]+)\""),
				"PathPrefixConstants.PROPERTY");
		String hosted = firstGroup(properties, Pattern.compile(
				"@ConfigurationProperties\\(prefix\\s*=\\s*\"([^\"]+)\"\\)"),
				"PathPrefixProperties 的 @ConfigurationProperties(prefix)");

		assertEquals(hosted + ".value", declared, """
				读前缀用的属性名与 MVC 加前缀绑定的属性名对不上。
				后果是「MVC 加了前缀、读的那一处还按老路径匹配」——2026-08-15 auth 那次
				12 个端点变 302（含登出失效）就是这个形态。""");
	}

	/**
	 * 读前缀的地方散在三个模块，各自写一份字面量就迟早写岔。判据：这些文件必须引用
	 * {@code PathPrefixConstants}，而不是自己拼 {@code "${zhiflow.path-prefix...}"}。
	 */
	private static final List<String> PREFIX_READERS = List.of(
			"zhiflow-common/zhiflow-common-security/src/main/java/com/zhiflow/"
					+ "common/security/component/SecurityScope.java",
			"zhiflow-common/zhiflow-common-security/src/main/java/com/zhiflow/"
					+ "common/security/component/PermitAllUrlProperties.java",
			"zhiflow-common/zhiflow-common-portal/src/main/java/com/zhiflow/"
					+ "common/portal/config/WebConfig.java");

	@Test
	@DisplayName("读前缀的地方一律走 PathPrefixConstants，不许各自拼属性名字面量")
	void prefixReadersUseTheSharedConstant() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (String relative : PREFIX_READERS) {
			Path file = REPO_ROOT.resolve(relative);
			assertTrue(Files.isRegularFile(file), "找不到读前缀的文件：" + file
					+ "（判据本身失效比规则不通过更危险：门禁会静默变成永远通过）");
			String source = Files.readString(file, StandardCharsets.UTF_8);
			if (!source.contains("PathPrefixConstants")) {
				offenders.add(relative);
			}
			if (source.contains("\"${zhiflow.path-prefix")) {
				offenders.add(relative + "（自己拼了占位符字面量）");
			}
		}
		assertTrue(offenders.isEmpty(), () -> """
				这些地方没走共享常量：
				%s
				前缀是一个配置值、四处在读（安全链管辖范围、两处白名单、一处拦截器 pattern），
				其中两处的失配是权限矩阵探不到的：支付回调要登录态、未识别参数闸静默失效。""".formatted(
						String.join("\n", offenders)));
	}

	/**
	 * 两个「安全决策点」：一个决定链怎么建（{@code BaseResourceServerConfiguration}），
	 * 一个决定白名单装哪些（{@code PermitAllUrlProperties}）。改造前它们各自按应用名分支。
	 *
	 * <p><b>规则刻意只盯这两个文件，而不是整个 common-security</b>：把应用名读出来<b>本身</b>
	 * 不是缺陷（{@code GlobalBizExceptionHandler} 就拿它打日志，完全正当），
	 * 缺陷是<b>拿它决定放行与否</b>。整模块禁读会把正当用法一起判死，
	 * 而那种规则通常的下场是被加一条豁免、然后没人再看它。
	 */
	private static final List<String> SECURITY_DECISION_POINTS = List.of(
			"zhiflow-common/zhiflow-common-security/src/main/java/com/zhiflow/"
					+ "common/security/component/BaseResourceServerConfiguration.java",
			"zhiflow-common/zhiflow-common-security/src/main/java/com/zhiflow/"
					+ "common/security/component/PermitAllUrlProperties.java");

	@Test
	@DisplayName("安全决策点不得再按 spring.application.name 分支，且必须走 SecurityScope")
	void securityDecisionPointsDoNotBranchOnApplicationName() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (String relative : SECURITY_DECISION_POINTS) {
			Path file = REPO_ROOT.resolve(relative);
			assertTrue(Files.isRegularFile(file), "找不到安全决策点：" + file
					+ "（判据本身失效比规则不通过更危险：门禁会静默变成永远通过）");

			String source = Files.readString(file, StandardCharsets.UTF_8);
			assertTrue(source.contains("SecurityScope"), relative
					+ " 没有引用 SecurityScope —— 认证机制/管辖范围又回到了别处判定");

			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (!isComment(line) && line.contains("spring.application.name")) {
					offenders.add(relative + ":" + (i + 1) + "  " + line.trim());
				}
			}
		}
		assertTrue(offenders.isEmpty(), () -> """
				安全决策点里又出现了按应用名分支的写法：
				%s
				一个共享库里写死某个服务的名字，在改名 / 拆服务 / 阶段 3 合并进单体时会让
				安全姿态静默翻转，而编译期与日志都发现不了。改成显式声明 %s。""".formatted(
						String.join("\n", offenders), MODE_KEY));
	}

	// ------------------------------------------------------------------ helpers

	private static boolean isComment(String line) {
		String trimmed = line.trim();
		return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
	}

	private static String firstGroup(Path file, Pattern pattern, String what) throws IOException {
		Matcher matcher = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
		if (!matcher.find()) {
			return fail("在 " + file.getFileName() + " 里找不到 " + what + " —— 判据本身失效了，"
					+ "这比规则不通过更危险：门禁会静默变成永远通过");
		}
		return matcher.group(1);
	}

	private static List<Path> serviceApplicationYmls() throws IOException {
		try (Stream<Path> files = Files.walk(REPO_ROOT, 6)) {
			return files.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().equals("application.yml"))
				.filter(p -> p.toString().contains("src" + java.io.File.separator + "main"
						+ java.io.File.separator + "resources"))
				.filter(p -> !p.toString().contains(java.io.File.separator + "target"
						+ java.io.File.separator))
				// ⚠️ 排掉 .claude/worktrees：整份仓库副本（常是别的分支），
				// 算进来会把别人分支的 application.yml 当成本仓的安全配置来判定。
				.filter(p -> !p.toString().contains(java.io.File.separator + ".claude"
						+ java.io.File.separator))
				.toList();
		}
	}

	/**
	 * 把 YAML 的映射键压平成 {@code a.b.c -> 值}。只处理映射行，够用且不引依赖
	 * （本模块刻意不依赖 Spring 与业务模块，见 pom 说明）。
	 */
	private static Map<String, String> flatten(Path yml) throws IOException {
		Map<String, String> flat = new LinkedHashMap<>();
		Deque<int[]> indents = new ArrayDeque<>();
		Deque<String> keys = new ArrayDeque<>();
		for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.trim().startsWith("#")) {
				continue;
			}
			Matcher matcher = KEY_LINE.matcher(line);
			if (!matcher.matches()) {
				continue;
			}
			int indent = matcher.group(1).length();
			while (!indents.isEmpty() && indents.peek()[0] >= indent) {
				indents.pop();
				keys.pop();
			}
			indents.push(new int[] { indent });
			keys.push(matcher.group(2));

			String value = matcher.group(3).trim();
			int comment = value.indexOf(" #");
			if (comment >= 0) {
				value = value.substring(0, comment).trim();
			}
			if (!value.isEmpty()) {
				List<String> path = new ArrayList<>(keys);
				java.util.Collections.reverse(path);
				flat.put(String.join(".", path), value);
			}
		}
		return flat;
	}
}
