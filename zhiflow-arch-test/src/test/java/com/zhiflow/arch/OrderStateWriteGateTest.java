package com.zhiflow.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 支付与订单状态机 v1.2 · 7.2 第二层「CI 门禁」：订单 / 支付单 / 售后单的状态只能由各自的状态机写。
 *
 * <p>三层防护里的位置：第一层实体 {@code @TableField(updateStrategy = NEVER)} 挡整行写回；
 * 本门禁挡<b>新增代码</b>的两类写法；第三层 PG 触发器挡运行时拼 SQL。
 *
 * <ol>
 *   <li><b>ArchUnit</b>：{@code trade.statemachine} 包外禁止调用 {@code OrderInfo.setStatus / setIsPay}、
 *       {@code PayOrder.setStatus}、{@code OrderRefunds.setStatus}。
 *       例外只有<b>显式登记</b>的 {@link #ALLOWED} 三类（建单初值、按例查询条件、状态机返回后的内存同步），
 *       每条带理由；登记项不再存在时同样失败（只降不升，与 R1d 同一做法，不用 FreezingArchRule——
 *       目标是新增为 0，而且规则文案一改 baseline 就会被静默重建，见 {@link BaselineIntegrityTest}）。</li>
 *   <li><b>源码扫描</b>（v1.2 原文「grep 脚本」，落在这里与 ArchUnit 同跑，CI 停摆时本地 {@code mvn test} 也能拦）：
 *       lambda {@code .set(OrderInfo::getStatus / getIsPay)}、mapper XML {@code <update>} 与 {@code @Update}
 *       里对 order_info / pay_order / order_refunds 的 {@code status = / is_pay =}。白名单只有三个状态机自己的 mapper。</li>
 * </ol>
 *
 * <p>为什么 setter 也要管：实体层 NEVER 之后，{@code setStatus + updateById} 不再落库——
 * 这类调用<b>静默失效</b>比写错更危险（代码看着改了状态，库里没变）。门禁把它们全部逼出来。
 */
class OrderStateWriteGateTest {

	/** 受保护实体 → 状态 setter（订单：status / is_pay；支付单：status）。售后单随退款功能删除（2026-10-20 开源分支）。 */
	private static final Map<String, Set<String>> STATE_SETTERS = Map.of(
			"com.zhiflow.mall.common.entity.OrderInfo", Set.of("setStatus", "setIsPay"),
			"com.zhiflow.mall.common.entity.PayOrder", Set.of("setStatus"));
	private static final String STATE_MACHINE_PACKAGE = "com.zhiflow.mall.common.trade.statemachine";

	/**
	 * 显式登记的例外：{@code 简单类名#方法名} → 理由。只允许删，不允许加（加 = 放行新的状态写入口，先改走状态机）。
	 */
	static final Map<String, String> ALLOWED = Map.ofEntries(
			// 按例查询条件：new OrderInfo() 只作为 page2 / sumPaymentPrice 的过滤对象，不落库
			Map.entry("LargeScreenController#sumSales", "按例查询条件（销售额 isPay=1）"));

	private static JavaClasses classes;

	@BeforeAll
	static void importClasses() {
		classes = ArchScope.importAll();
	}

	@Test
	@DisplayName("ArchUnit：状态机包外不得调用 OrderInfo.setStatus / setIsPay、PayOrder.setStatus（登记例外除外）")
	void orderStateSettersOnlyInsideStateMachine() {
		Set<String> offenders = new TreeSet<>();
		Set<String> seenAllowed = new TreeSet<>();
		scan(classes, offenders, seenAllowed);
		scan(ArchScope.importEntities(), offenders, seenAllowed);
		if (!offenders.isEmpty()) {
			fail("状态机包外调用了状态 setter（改走 OrderStateMachine / PayOrderStateMachine.fire，或在状态机包内提供同步方法）：\n  "
					+ String.join("\n  ", offenders));
		}
		Set<String> stale = new TreeSet<>(ALLOWED.keySet());
		stale.removeAll(seenAllowed);
		assertTrue(stale.isEmpty(), "登记的例外已不存在，请从 ALLOWED 删除：" + stale);
	}

	private static void scan(JavaClasses scope, Set<String> offenders, Set<String> seenAllowed) {
		for (JavaClass c : scope) {
			if (c.getPackageName().startsWith(STATE_MACHINE_PACKAGE)) {
				continue;
			}
			for (JavaMethodCall call : c.getMethodCallsFromSelf()) {
				Set<String> setters = STATE_SETTERS.get(call.getTargetOwner().getName());
				if (setters == null || !setters.contains(call.getTarget().getName())) {
					continue;
				}
				String key = topLevel(c).getSimpleName() + "#" + call.getOrigin().getName();
				if (ALLOWED.containsKey(key)) {
					seenAllowed.add(key);
				}
				else {
					offenders.add(key + " → " + call.getTargetOwner().getSimpleName() + "." + call.getTarget().getName()
							+ " @ " + call.getSourceCodeLocation());
				}
			}
		}
	}

	private static JavaClass topLevel(JavaClass c) {
		JavaClass cur = c;
		while (cur.getEnclosingClass().isPresent()) {
			cur = cur.getEnclosingClass().get();
		}
		return cur;
	}

	// ─────────────────────────────── 源码扫描（v1.2「grep 脚本」） ───────────────────────────────

	/** 状态机自己的 mapper：订单两服务各一份（D10）、支付单一份，只有它们能写状态列。 */
	private static final Set<String> SQL_ALLOWED_FILES = Set.of("OrderStateMachineMapper.java",
			"PayOrderStateMachineMapper.java");

	private static final Pattern LAMBDA_SET = Pattern.compile(
			"\\.set\\(\\s*(OrderInfo::get(?:Status|IsPay)|PayOrder::getStatus)\\b");
	/** 字符串列名的 UpdateWrapper：只在引用了 OrderInfo 的文件里查。 */
	private static final Pattern STRING_SET = Pattern.compile("\\.set\\(\\s*\"(status|is_pay)\"");
	/** SQL：UPDATE order_info … 到 WHERE（或 500 字符）之间出现 status = / is_pay =。XML 与注解 SQL 同一判据。 */
	private static final Pattern SQL_UPDATE_ORDER_INFO = Pattern.compile("(?i)\\bupdate\\s+(order_info|pay_order|order_refunds)\\b");
	private static final Pattern SQL_WHERE = Pattern.compile("(?i)\\bwhere\\b");
	private static final Pattern SQL_STATE_ASSIGN = Pattern.compile("(?i)(?<!\\w)(status|is_pay)\\s*=");

	@Test
	@DisplayName("源码扫描：lambda .set(OrderInfo::getStatus/getIsPay) 与 SQL 直写 order_info 状态")
	void noDirectOrderStateSqlOrLambdaSet() throws IOException {
		Path root = repoRoot();
		List<String> offenders = new ArrayList<>();
		for (Path p : mainSources(root)) {
			if (!SQL_ALLOWED_FILES.contains(p.getFileName().toString())) {
				offenders.addAll(findStateWrites(root.relativize(p).toString(), Files.readString(p)));
			}
		}
		if (!offenders.isEmpty()) {
			fail("绕过状态机写 order_info.status / is_pay（改走 OrderStateMachine.fire）：\n  "
					+ String.join("\n  ", offenders));
		}
	}

	/**
	 * 对照样本：扫描器必须能认出状态机自己的 mapper（去掉白名单时应报出来）。
	 * 否则「零命中」可能只是扫描器坏了（没读到文件、正则写错）——门禁静默全绿是最危险的失效。
	 */
	@Test
	@DisplayName("对照样本：扫描器能认出白名单里的状态机 mapper 与一个人造的 lambda 写法")
	void scannerDetectsKnownPositives() throws IOException {
		Path root = repoRoot();
		List<Path> mappers = mainSources(root).stream()
				.filter(p -> SQL_ALLOWED_FILES.contains(p.getFileName().toString())).toList();
		// 2026-10-20 开源分支：售后（退款）随功能删除，售后单状态机 mapper 两份没了
		assertTrue(mappers.size() >= 3, "应当扫到订单状态机 mapper 两份 + 支付单状态机 mapper 一份，实际 " + mappers);
		for (Path m : mappers) {
			assertTrue(!findStateWrites(m.toString(), Files.readString(m)).isEmpty(), "扫描器没认出 " + m);
		}
		assertTrue(!findStateWrites("X.java",
				"class X { void f(){ Wrappers.<OrderInfo>lambdaUpdate().set(OrderInfo::getStatus, \"5\"); } }").isEmpty());
		assertTrue(!findStateWrites("X.xml",
				"<update id=\"x\">UPDATE order_info <set><if test=\"a\">is_pay = #{a}</if></set> WHERE id=#{id}</update>").isEmpty());
		assertTrue(!findStateWrites("X.java",
				"@Update(\"UPDATE pay_order SET status = 'PAID' WHERE pay_no = #{p}\")").isEmpty());
		// 反向对照：只改退款占额、不写状态的 pay_order UPDATE 不算
		assertTrue(findStateWrites("X.java",
				"@Update(\"UPDATE pay_order SET attach = #{a} WHERE pay_no = #{p} AND status = 'PAID'\")").isEmpty());
		// 反向对照：WHERE 里的 status = 不是写
		assertTrue(findStateWrites("X.xml",
				"<update id=\"x\">UPDATE order_info SET remark = #{r} WHERE status = '1' AND is_pay = '1'</update>").isEmpty());
	}

	private static List<Path> mainSources(Path root) throws IOException {
		List<Path> out = new ArrayList<>();
		for (String module : List.of("zhiflow-mall", "zhiflow-pay", "zhiflow-upms", "zhiflow-im",
				"zhiflow-live", "zhiflow-weixin", "zhiflow-boot")) {
			Path dir = root.resolve(module);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(dir)) {
				files.filter(p -> p.toString().contains("/src/main/"))
						.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".xml"))
						.forEach(out::add);
			}
		}
		// 2026-10-20 开源分支删掉大批功能、再清掉死表代码（阶段 6 基线前）后实测 696 个；这里只防扫描范围失效
		assertTrue(out.size() > 600, "扫到的源文件过少（" + out.size() + "），扫描范围可能失效");
		return out;
	}

	static List<String> findStateWrites(String rel, String text) {
		List<String> hits = new ArrayList<>();
		if (rel.endsWith(".java")) {
			Matcher m = LAMBDA_SET.matcher(text);
			while (m.find()) {
				hits.add(rel + ":" + line(text, m.start()) + " lambda .set(" + m.group(1) + ")");
			}
			if (text.contains("OrderInfo") || text.contains("PayOrder") || text.contains("OrderRefunds")) {
				Matcher s = STRING_SET.matcher(text);
				while (s.find()) {
					hits.add(rel + ":" + line(text, s.start()) + " UpdateWrapper .set(\"" + s.group(1) + "\")");
				}
			}
		}
		Matcher u = SQL_UPDATE_ORDER_INFO.matcher(text);
		while (u.find()) {
			int from = u.end();
			int to = Math.min(text.length(), from + 500);
			Matcher w = SQL_WHERE.matcher(text).region(from, to);
			if (w.find()) {
				to = w.start();
			}
			if (SQL_STATE_ASSIGN.matcher(text.substring(from, to)).find()) {
				hits.add(rel + ":" + line(text, u.start()) + " SQL UPDATE " + u.group(1) + " 写状态列");
			}
		}
		return hits;
	}

	private static int line(String text, int offset) {
		int n = 1;
		for (int i = 0; i < offset; i++) {
			if (text.charAt(i) == '\n') {
				n++;
			}
		}
		return n;
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
