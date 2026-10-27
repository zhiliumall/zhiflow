package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 验收：「CI grep 门禁在 statemachine 与 job 包内禁止出现 {@code instanceof WxPayChannel}、
 * {@code "WXPAY".equals} 这类按渠道分支」。渠道差异一律走能力矩阵（{@code ChannelCapabilities} + 配置覆盖），不写渠道分支。
 *
 * <p>范围：状态机包、定时任务包，以及支付编排层（{@code payment/} 顶层类；各渠道实现 {@code payment/channel/} 除外——
 * 渠道自己当然认识自己）。
 */
class ChannelBranchGateTest {

	static final Pattern CHANNEL_BRANCH = Pattern.compile(
			"instanceof\\s+\\w*Channel\\b|\"(?i:wxpay|wechat|alipay)\"\\s*\\.equals(IgnoreCase)?\\(|\\.equals(IgnoreCase)?\\(\\s*\"(?i:wxpay|wechat|alipay)\"");

	@Test
	@DisplayName("14.1：状态机 / 定时任务 / 支付编排层不写「如果是某渠道」的分支")
	void noChannelBranches() throws IOException {
		List<String> hits = new ArrayList<>();
		for (Path p : scoped(repoRoot())) {
			hits.addAll(find(p.toString(), Files.readString(p)));
		}
		if (!hits.isEmpty()) {
			fail("渠道差异请声明在 ChannelCapabilities（或配置 pay.channel.<code>.cap.*），不要写渠道分支：\n  "
					+ String.join("\n  ", hits));
		}
	}

	@Test
	@DisplayName("对照样本：扫描器认得出两种写法，扫描范围非空")
	void scannerDetectsKnownPositives() throws IOException {
		assertFalse(find("X.java", "if (ch instanceof WxPayChannel c) {}").isEmpty());
		assertFalse(find("X.java", "if (\"WXPAY\".equals(code)) {}").isEmpty());
		assertFalse(find("X.java", "if (code.equalsIgnoreCase(\"AliPay\")) {}").isEmpty());
		assertTrue(find("X.java", "ChannelCapabilities caps = resolver.of(code);").isEmpty());
		assertTrue(scoped(repoRoot()).size() > 20, "扫描范围过小，路径规则可能失效");
	}

	static List<String> find(String rel, String text) {
		List<String> hits = new ArrayList<>();
		Matcher m = CHANNEL_BRANCH.matcher(text);
		while (m.find()) {
			hits.add(rel + " → " + m.group());
		}
		return hits;
	}

	private static List<Path> scoped(Path root) throws IOException {
		List<Path> out = new ArrayList<>();
		Path mall = root.resolve("zhiflow-mall");
		try (Stream<Path> files = Files.walk(mall)) {
			files.filter(p -> p.toString().contains("/src/main/java/") && p.toString().endsWith(".java"))
					.filter(p -> {
						String s = p.toString();
						return s.contains("/trade/statemachine/") || s.contains("/job/")
								|| (s.contains("/payment/") && !s.contains("/payment/channel/"));
					})
					.forEach(out::add);
		}
		return out;
	}

	private static Path repoRoot() {
		Path p = Path.of("").toAbsolutePath();
		while (p != null && !Files.exists(p.resolve("zhiflow-arch-test"))) {
			p = p.getParent();
		}
		if (p == null) {
			throw new IllegalStateException("找不到仓库根（含 zhiflow-arch-test 的目录）");
		}
		return p;
	}
}
