package com.zhiflow.arch;

import org.junit.jupiter.api.BeforeAll;
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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [双审 P3-3] 契约 {@code required=true} 的 {@code @RequestParam} 必须在本地实现里有空值守卫。
 *
 * <h2>这条门禁是被一次「恒等」声称的翻车换来的</h2>
 * <p>spring-web 的 HTTP interface 客户端遇到 {@code required=true} 却为 null 的
 * {@code @RequestParam} 时，在<b>参数解析阶段</b>抛 {@code IllegalArgumentException} ——
 * <b>请求根本不发出</b>（spring-web 6.2.19 实证）。本地实现直调 controller 则会把 null
 * 一路透传进去，让「多进程形态下从来不会发生的调用」在单体形态下真的发生。
 * 2026-08-16 的对抗性审查在 {@code queryOrder} 上抓到这件事，
 * 而它恰好落在<b>补账入账</b>与<b>未支付单取消闸门</b>两条钱的路径上
 * （支付本地实现审计）。
 *
 * <p>更值得记的是<b>第一轮修复自己也漏了</b>：契约共 25 个必填 {@code @RequestParam}，
 * 第一轮只补了 23 个守卫（两个漏掉了），
 * 而当时的双审材料写的是「全部必填参数已加守卫」。
 * <b>人工清点在 25 个条目上就已经不可靠了</b> —— 所以判据必须是机器算的。
 *
 * <h2>判据</h2>
 * <ol>
 * <li>从<b>契约源码</b>算出全部 {@code required=true} 的 {@code @RequestParam}
 *     （没写 {@code required = false} 即为 true，与 Spring 语义一致）；</li>
 * <li>基本类型自动跳过（语言层面不可能是 null）；</li>
 * <li>契约若有本地实现（{@code **&#47;local&#47;*LocalApi.java}），该参数名必须出现在
 *     实现的同名方法体里、以 {@code requireParam(…, "参数名")} 的形式；</li>
 * <li>否则必须在 {@link ContractRequiredParamExemptions} 里登记<b>可复核的理由</b>。</li>
 * </ol>
 *
 * <h2>三条自检（防这条门禁自己变成假绿）</h2>
 * <p>读源码的规则有个共同的头号失效模式：扫描面没了、正则失配 ⇒ 集合为空 ⇒ 全部通过。
 * 所以下面有「契约数量下限」「必填参数量下限」「豁免表无死条目」三条反向断言。
 */
class ContractRequiredParamGuardGateTest {

	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	/** 契约接口散落在这几处（判据是接口上带 {@code @HttpExchange}，目录只用来缩小遍历面）。 */
	private static final List<String> CONTRACT_ROOTS = List.of(
			"zhiflow-api", "zhiflow-im/zhiflow-im-api", "zhiflow-live/zhiflow-live-common");

	/**
	 * 2026-08-16 实测：52 个契约接口、40 个必填 {@code @RequestParam}。
	 * 2026-10-20 开源分支删掉大批功能后实测：13 个契约接口、1 个必填参（OrderInfoApi 取消订单的 operationId）。
	 */
	private static final int MIN_CONTRACTS = 10;

	private static final int MIN_REQUIRED_PARAMS = 1;

	private static final Set<String> PRIMITIVES = Set.of(
			"int", "long", "short", "byte", "char", "float", "double", "boolean");

	private static List<RequiredParam> requiredParams;

	private static Map<String, String> localImplSourceByContract;

	private static int contractCount;

	record RequiredParam(String contract, String method, String param, String type) {
		String key() {
			return this.contract + "." + this.method + "(" + this.param + ")";
		}
	}

	@BeforeAll
	static void scan() {
		localImplSourceByContract = new LinkedHashMap<>();
		for (Path file : javaFilesUnder(REPO_ROOT)) {
			String name = file.getFileName().toString();
			if (!name.endsWith("LocalApi.java") || !file.getParent().getFileName().toString().equals("local")) {
				continue;
			}
			String src = JavaSource.stripComments(read(file));
			Matcher m = Pattern.compile("class\\s+\\w+\\s+implements\\s+([\\w,\\s.]+?)\\{").matcher(src);
			if (m.find()) {
				for (String itf : m.group(1).split(",")) {
					String simple = itf.trim();
					simple = simple.substring(simple.lastIndexOf('.') + 1);
					if (!simple.isEmpty()) {
						localImplSourceByContract.put(simple, src);
					}
				}
			}
		}

		requiredParams = new ArrayList<>();
		contractCount = 0;
		for (String root : CONTRACT_ROOTS) {
			for (Path file : javaFilesUnder(REPO_ROOT.resolve(root))) {
				String raw = read(file);
				if (!raw.contains("@HttpExchange")) {
					continue;
				}
				contractCount++;
				requiredParams.addAll(parseContract(JavaSource.stripComments(raw)));
			}
		}
	}

	// ================================================================= 判据自检

	@Test
	@DisplayName("自检：扫得到至少 " + MIN_CONTRACTS + " 个契约接口（扫空 = 下面全是假绿）")
	void contractScanIsNotEmpty() {
		assertTrue(contractCount >= MIN_CONTRACTS,
				"只扫到 " + contractCount + " 个契约接口，远少于实测的 13 —— 扫描面失效，本门禁毫无意义");
	}

	@Test
	@DisplayName("自检：扫得到至少 " + MIN_REQUIRED_PARAMS + " 个必填 @RequestParam（正则失配 = 假绿）")
	void requiredParamScanIsNotEmpty() {
		assertTrue(requiredParams.size() >= MIN_REQUIRED_PARAMS, () -> """
				只解析出 %d 个 required=true 的 @RequestParam，远少于实测的 1。
				多半是形参解析正则失配（换行/泛型/注解写法变了）——
				这类失效是**静默变绿**，必须当成红灯处理。""".formatted(requiredParams.size()));
	}

	@Test
	@DisplayName("自检：本地实现映射非空，且 ZhiflowPayApi 一定在其中（资金链路是本门禁的主要看护对象）")
	void localImplMappingIsSane() {
		assertFalse(localImplSourceByContract.isEmpty(), "一个 *LocalApi 都没扫到，本门禁形同虚设");
		assertTrue(localImplSourceByContract.containsKey("ZhiflowPayApi"), """
				没扫到 ZhiflowPayApi 的本地实现。它是 P1-1 的当事人，
				扫不到它 = 下面那条守卫断言对资金链路完全不起作用。""");
	}

	// ================================================================= 本体

	@Test
	@DisplayName("⭐ 每个必填 @RequestParam 要么有 requireParam 守卫，要么在豁免表里登记了理由")
	void everyRequiredParamIsGuardedOrExempted() {
		List<String> offenders = new ArrayList<>();
		for (RequiredParam p : requiredParams) {
			if (PRIMITIVES.contains(p.type())) {
				continue; // 基本类型不可能是 null
			}
			String implSource = localImplSourceByContract.get(p.contract());
			if (implSource == null) {
				continue; // 还没有本地实现 ⇒ 单体里走 HTTP 代理，客户端会照常抛 IAE，不存在分叉
			}
			if (hasGuard(implSource, p.method(), p.param())) {
				continue;
			}
			if (ContractRequiredParamExemptions.ALLOWED.containsKey(p.key())) {
				continue;
			}
			offenders.add(p.key() + "  （契约 " + p.contract() + "，类型 " + p.type() + "）");
		}

		assertTrue(offenders.isEmpty(), () -> """
				这些 required=true 的 @RequestParam 在本地实现里既没有空值守卫、也没在
				ContractRequiredParamExemptions 登记：
				%s

				为什么这是问题：HTTP interface 客户端遇到 required=true 的 null 会在
				**发请求之前**抛 IllegalArgumentException（spring-web 6.2 实证），
				而本地实现会把 null 透传进 controller —— 同一份代码，多进程形态调用没发生、
				单体形态真的发生了。queryOrder 上这件事落在补账与取消闸门上（P1-1）。

				两条出路：
				① 加守卫（形如 requireParam(x, "x")，抛 spring-web 同款
				   IllegalArgumentException("Missing request parameter value 'x'")）；
				② 在豁免表登记，理由必须是**可复核的事实**（调用点实参是什么、可不可能为 null），
				   不接受「应该不会为 null」。""".formatted(String.join("\n", offenders)));
	}

	@Test
	@DisplayName("⭐ 守卫覆盖度自检：ZhiflowPayApi 的必填 @RequestParam 必须 100% 有守卫（零豁免）")
	void payContractHasFullGuardCoverage() {
		String implSource = localImplSourceByContract.get("ZhiflowPayApi");
		assertTrue(implSource != null, "扫不到 ZhiflowPayLocalApi");

		List<String> missing = new ArrayList<>();
		int total = 0;
		for (RequiredParam p : requiredParams) {
			if (!"ZhiflowPayApi".equals(p.contract()) || PRIMITIVES.contains(p.type())) {
				continue;
			}
			total++;
			if (!hasGuard(implSource, p.method(), p.param())) {
				missing.add(p.key());
			}
		}

		// 开源分支（2026-10-20）的 pay 契约只剩 @RequestBody / @PathVariable 参数，必填 @RequestParam 实测 0 个，
		// 不再钉数量；本条退化为「以后新增的必填参必须带守卫」。扫描面是否失效由上面两条自检兜。
		assertTrue(missing.isEmpty(), () -> """
				资金链路契约上还有 %d 个必填参数没有守卫：%s
				⚠️ pay **不接受豁免**：它是资金链路，
				任何一处形态分叉都直接落在钱上。
				（第一轮 P1-1 修复就曾在这类参数上漏过两个 ——
				 25 个条目的人工清点已经不可靠，所以这条断言存在。）""".formatted(missing.size(), missing));
	}

	@Test
	@DisplayName("守卫：豁免表里不许留下已经补了守卫 / 已经不存在的条目")
	void exemptionsHaveNoStaleEntries() {
		Set<String> live = new TreeSet<>();
		for (RequiredParam p : requiredParams) {
			live.add(p.key());
		}

		List<String> stale = new ArrayList<>();
		for (String key : ContractRequiredParamExemptions.ALLOWED.keySet()) {
			if (!live.contains(key)) {
				stale.add(key + "（契约里已经没有这个必填参数了）");
				continue;
			}
			RequiredParam p = requiredParams.stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
			String implSource = localImplSourceByContract.get(p.contract());
			if (implSource != null && hasGuard(implSource, p.method(), p.param())) {
				stale.add(key + "（守卫已补上）");
			}
		}

		assertTrue(stale.isEmpty(), () -> """
				这些豁免条目已经过时，却还留在 ContractRequiredParamExemptions 里：
				%s
				死条目会让下一个人以为缺口还在（本仓的反复教训：过期的登记比没登记更误导）。删掉它们。"""
				.formatted(String.join("\n", stale)));
	}

	// ================================================================= 解析

	/** 本地实现的 {@code 方法名} 方法体里有没有 {@code requireParam(…, "参数名")}。 */
	private static boolean hasGuard(String implSource, String method, String param) {
		String body = methodBody(implSource, method);
		if (body == null) {
			return false;
		}
		return Pattern.compile("requireParam\\s*\\([^;]*?\"" + Pattern.quote(param) + "\"\\s*\\)")
				.matcher(body).find();
	}

	/** 取实现里该方法的方法体（从签名的 {@code {} 到配对的 {@code }}）。 */
	private static String methodBody(String src, String method) {
		Matcher m = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(").matcher(src);
		while (m.find()) {
			int paren = src.indexOf('(', m.start());
			int close = matching(src, paren, '(', ')');
			if (close < 0) {
				continue;
			}
			int brace = src.indexOf('{', close);
			if (brace < 0) {
				continue;
			}
			// 签名与 { 之间只允许空白 / throws 子句；否则这是个调用而不是声明
			String between = src.substring(close + 1, brace);
			if (!between.isBlank() && !between.contains("throws")) {
				continue;
			}
			int end = matching(src, brace, '{', '}');
			if (end > 0) {
				return src.substring(brace, end + 1);
			}
		}
		return null;
	}

	private static int matching(String s, int open, char oc, char cc) {
		int depth = 0;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == oc) {
				depth++;
			}
			else if (c == cc) {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	/** 从（已剥注释的）契约源码里解析出全部 required=true 的 {@code @RequestParam}。 */
	private static List<RequiredParam> parseContract(String src) {
		List<RequiredParam> out = new ArrayList<>();
		Matcher im = Pattern.compile("\\binterface\\s+(\\w+)").matcher(src);
		if (!im.find()) {
			return out;
		}
		String contract = im.group(1);

		int bodyStart = src.indexOf('{', im.end());
		if (bodyStart < 0) {
			return out;
		}
		int depth = 0;
		int segStart = bodyStart + 1;
		for (int i = bodyStart; i < src.length(); i++) {
			char c = src.charAt(i);
			if (c == '{') {
				depth++;
			}
			else if (c == '}') {
				depth--;
				if (depth == 0) {
					break;
				}
			}
			else if (c == ';' && depth == 1) {
				out.addAll(parseMethod(contract, src.substring(segStart, i)));
				segStart = i + 1;
			}
		}
		return out;
	}

	private static List<RequiredParam> parseMethod(String contract, String segment) {
		List<RequiredParam> out = new ArrayList<>();
		int rp = segment.lastIndexOf(')');
		if (rp < 0) {
			return out;
		}
		int depth = 0;
		int lp = -1;
		for (int i = rp; i >= 0; i--) {
			char c = segment.charAt(i);
			if (c == ')') {
				depth++;
			}
			else if (c == '(') {
				depth--;
				if (depth == 0) {
					lp = i;
					break;
				}
			}
		}
		if (lp < 0) {
			return out;
		}
		// 方法名 = 签名部分（注解已在括号里，这里安全）的最后一个标识符
		String head = segment.substring(0, lp);
		Matcher idm = Pattern.compile("[A-Za-z_$][\\w$]*").matcher(head);
		String method = null;
		while (idm.find()) {
			method = idm.group();
		}
		if (method == null) {
			return out;
		}

		for (String p : splitTopLevel(segment.substring(lp + 1, rp))) {
			Matcher ann = Pattern.compile("@RequestParam(\\s*\\(([^)]*)\\))?").matcher(p);
			if (!ann.find()) {
				continue;
			}
			String args = ann.group(2) == null ? "" : ann.group(2);
			if (args.replace(" ", "").contains("required=false")) {
				continue;
			}
			// 去掉全部注解后剩「类型 形参名」
			String tail = p.replaceAll("@\\w+\\s*(\\([^)]*\\))?", " ").trim();
			String[] tokens = tail.split("\\s+");
			if (tokens.length < 2) {
				continue;
			}
			String name = tokens[tokens.length - 1].replaceAll("[^\\w$]", "");
			String type = String.join(" ", java.util.Arrays.copyOf(tokens, tokens.length - 1)).trim();
			// 泛型里的空格（Map<String, Object>）会被上面切开，只取首个记号判基本类型足够
			out.add(new RequiredParam(contract, method, name, type.split("[<\\s]")[0]));
		}
		return out;
	}

	/** 按顶层逗号切形参（注解参数里也有逗号）。 */
	private static List<String> splitTopLevel(String text) {
		List<String> parts = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		int depth = 0;
		for (char ch : text.toCharArray()) {
			if (ch == '(' || ch == '<' || ch == '[') {
				depth++;
			}
			else if (ch == ')' || ch == '>' || ch == ']') {
				depth--;
			}
			if (ch == ',' && depth == 0) {
				parts.add(cur.toString().trim());
				cur.setLength(0);
			}
			else {
				cur.append(ch);
			}
		}
		if (!cur.toString().isBlank()) {
			parts.add(cur.toString().trim());
		}
		return parts;
	}

	private static List<Path> javaFilesUnder(Path root) {
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".java"))
					.filter(p -> !p.toString().contains(java.io.File.separator + "target"
							+ java.io.File.separator))
					// .claude/worktrees 下是整份仓库副本（常是别的分支）——
					// 算进来会让门禁的判定**随磁盘上有哪些 worktree 而变**。
					.filter(p -> !p.toString().contains(java.io.File.separator + ".claude"
							+ java.io.File.separator))
					.toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
