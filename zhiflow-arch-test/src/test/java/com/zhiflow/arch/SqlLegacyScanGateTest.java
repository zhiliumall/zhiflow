package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * [2026-10-01 CARD-15] 迁移遗留 SQL 门禁：MySQL 方言写法与两类危险形状，<b>只许变少</b>。
 *
 * <h2>为什么要门禁而不是一次性普查</h2>
 * <p>这个项目是从 MySQL 批量转成 PostgreSQL 的，转换遗留曾造成多轮 500
 * （类型错配、严格 GROUP BY、双引号字符串……，见 pg-type-binding-contract.md）。
 * 2026-10-01 实测：mapper XML 与 Java 侧的<b>方言写法已经清零</b>，应用侧的
 * SQL 语法类报错也全部已修。这条门禁守的是「别再长回来」：
 * 新写一条 {@code IFNULL} / 反引号 / {@code LIMIT a,b} 在 MySQL 习惯里顺手，在这里是上线即 500。
 *
 * <h2>判据与基线</h2>
 * <ul>
 * <li>规则与取材范围见 {@link SqlLegacyScanner}。</li>
 * <li>存量登记在 {@code sql-legacy-scan-baseline.txt}，按「规则 + 文件（XML 再加语句 id）」计数。
 * <b>多一条 → 红</b>（挡新增）；<b>少一条 → 也红</b>，提示把基线改小（挡基线变成没人维护的旧账，
 * 与 {@code ContractProtoShapeGateTest} 同一做法）。</li>
 * <li>{@code QUERY_BY_ENTITY}（把整个实体当查询条件）是模板代码的存量写法，基线最大；按月清理，
 * C 端控制器里的优先（CARD-19 的越权门禁会在 mall-api 控制器层再收一道）。</li>
 * </ul>
 *
 * <h2>两个环境变量（给 {@code ops/sql-scan/scan.sh} 用）</h2>
 * <ul>
 * <li>{@code SQL_SCAN_REPORT=<路径>}：把全部命中（含基线里的存量）写成 markdown 报告。</li>
 * <li>{@code SQL_SCAN_WRITE_BASELINE=1}：按当前命中重写基线文件，<b>然后照样判红</b>——
 * 重写基线必须有人审 diff 再提交，不能让它在 CI 里悄悄变绿。</li>
 * </ul>
 */
class SqlLegacyScanGateTest {

	static final Path BASELINE = Path.of("src/test/resources/sql-legacy-scan-baseline.txt");

	@Test
	@DisplayName("迁移遗留 SQL：不得新增方言写法 / 危险形状，存量只许变少")
	void legacySqlOnlyShrinks() throws IOException {
		Path root = repoRoot();
		List<SqlLegacyScanner.Finding> findings = SqlLegacyScanner.scan(root);

		String report = System.getenv("SQL_SCAN_REPORT");
		if (report != null && !report.isBlank()) {
			Files.writeString(Path.of(report), renderReport(findings), StandardCharsets.UTF_8);
		}

		Map<String, Integer> actual = count(findings);
		if (System.getenv("SQL_SCAN_WRITE_BASELINE") != null) {
			Files.writeString(BASELINE, renderBaseline(actual), StandardCharsets.UTF_8);
			fail("已按当前命中重写基线 " + BASELINE + "（" + actual.size() + " 个键）。请审阅 git diff 后提交，再去掉 "
					+ "SQL_SCAN_WRITE_BASELINE 重跑。");
		}

		Diff diff = diff(actual, loadBaseline(BASELINE));
		List<String> problems = new ArrayList<>();
		if (!diff.added().isEmpty()) {
			problems.add("新增了 " + diff.added().size() + " 处迁移遗留 SQL（PG 上必然报错或语义不同）：");
			for (String key : diff.added()) {
				problems.add("  + " + key.replace('\t', ' ') + describe(key, findings));
			}
		}
		if (!diff.shrunk().isEmpty()) {
			problems.add("存量减少了（好事），请把 " + BASELINE.getFileName() + " 里对应行改小或删掉：");
			for (String key : diff.shrunk()) {
				problems.add("  - " + key.replace('\t', ' '));
			}
		}
		if (!problems.isEmpty()) {
			fail(String.join("\n", problems));
		}
	}

	private static String describe(String key, List<SqlLegacyScanner.Finding> findings) {
		return findings.stream()
				.filter(f -> f.key().equals(key))
				.map(f -> "\n      L" + f.line() + "  " + f.snippet() + "\n      ⇒ " + hint(f.rule()))
				.collect(Collectors.joining());
	}

	static String hint(String rule) {
		for (SqlLegacyScanner.DialectRule r : SqlLegacyScanner.DIALECT_RULES) {
			if (r.id().equals(rule)) {
				return r.hint();
			}
		}
		return switch (rule) {
			case "MYSQL_DQ_LITERAL" -> SqlLegacyScanner.XML_DQ_LITERAL.hint();
			case SqlLegacyScanner.SELECT_ONE_ALL_OPTIONAL ->
				"单值返回但 WHERE 全可空且无 LIMIT：入参全空时命中整表 → TooManyResultsException → 500。"
						+ "加一个硬条件，或 LIMIT 1 + 有据的 ORDER BY，必填参数在入口拒（见 database-guidelines.md）";
			case SqlLegacyScanner.QUERY_BY_ENTITY ->
				"整个实体当查询条件：请求体里任何字段都会变成 WHERE 条件（含 userId/tenantId 一类），"
						+ "改为显式列出允许的条件";
			default -> "";
		};
	}

	// ------------------------------------------------------------ 计数与比对（供自测直接调用）

	record Diff(List<String> added, List<String> shrunk) {
	}

	static Map<String, Integer> count(List<SqlLegacyScanner.Finding> findings) {
		Map<String, Integer> m = new TreeMap<>();
		for (SqlLegacyScanner.Finding f : findings) {
			m.merge(f.key(), 1, Integer::sum);
		}
		return m;
	}

	static Diff diff(Map<String, Integer> actual, Map<String, Integer> baseline) {
		List<String> added = new ArrayList<>();
		List<String> shrunk = new ArrayList<>();
		actual.forEach((k, n) -> {
			if (n > baseline.getOrDefault(k, 0)) {
				added.add(k);
			}
		});
		baseline.forEach((k, n) -> {
			if (n > actual.getOrDefault(k, 0)) {
				shrunk.add(k);
			}
		});
		return new Diff(added, shrunk);
	}

	static Map<String, Integer> loadBaseline(Path file) {
		Map<String, Integer> m = new TreeMap<>();
		if (!Files.exists(file)) {
			return m;
		}
		try {
			for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String line = raw.strip();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("\t");
				if (parts.length != 3) {
					throw new IllegalStateException("基线行格式应为「规则<TAB>文件[#语句id]<TAB>条数」：" + raw);
				}
				m.put(parts[0] + "\t" + parts[1], Integer.parseInt(parts[2]));
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return m;
	}

	static String renderBaseline(Map<String, Integer> actual) {
		StringBuilder sb = new StringBuilder();
		sb.append("# 迁移遗留 SQL 存量基线（由 SqlLegacyScanGateTest 校验，只许变短）\n");
		sb.append("#\n");
		sb.append("# 格式：规则<TAB>文件[#mapper 语句 id]<TAB>条数\n");
		sb.append("# 规则说明见 SqlLegacyScanner；治掉一条就把对应条数改小 / 删行，并在提交说明里写清楚。\n");
		sb.append("# 生成：").append(LocalDate.now()).append("（CARD-15）。按月清，C 端控制器里的 QUERY_BY_ENTITY 优先。\n");
		sb.append("\n");
		actual.forEach((k, n) -> sb.append(k).append('\t').append(n).append('\n'));
		return sb.toString();
	}

	static String renderReport(List<SqlLegacyScanner.Finding> findings) {
		Map<String, List<SqlLegacyScanner.Finding>> byRule = new TreeMap<>();
		for (SqlLegacyScanner.Finding f : findings) {
			byRule.computeIfAbsent(f.rule(), k -> new ArrayList<>()).add(f);
		}
		StringBuilder sb = new StringBuilder();
		sb.append("# 迁移遗留 SQL 静态扫描报告\n\n");
		sb.append("- 生成：").append(LocalDate.now()).append("（`ops/sql-scan/scan.sh`，规则见 `SqlLegacyScanner`）\n");
		sb.append("- 只扫发往 PostgreSQL 的 SQL：mapper XML、Mapper 注解、条件构造器原生片段、只连 PG 的 JdbcTemplate 文件。\n\n");
		sb.append("| 规则 | 命中 | 涉及文件 | 说明 |\n|---|---:|---:|---|\n");
		for (SqlLegacyScanner.DialectRule r : SqlLegacyScanner.DIALECT_RULES) {
			row(sb, r.id(), byRule.get(r.id()), r.hint());
		}
		row(sb, "MYSQL_DQ_LITERAL", byRule.get("MYSQL_DQ_LITERAL"), SqlLegacyScanner.XML_DQ_LITERAL.hint());
		row(sb, SqlLegacyScanner.SELECT_ONE_ALL_OPTIONAL, byRule.get(SqlLegacyScanner.SELECT_ONE_ALL_OPTIONAL),
				"单值返回 + WHERE 全可空 + 无 LIMIT");
		row(sb, SqlLegacyScanner.QUERY_BY_ENTITY, byRule.get(SqlLegacyScanner.QUERY_BY_ENTITY), "整个实体当查询条件");
		for (Map.Entry<String, List<SqlLegacyScanner.Finding>> e : byRule.entrySet()) {
			sb.append("\n## ").append(e.getKey()).append("（").append(e.getValue().size()).append("）\n\n");
			for (SqlLegacyScanner.Finding f : e.getValue()) {
				sb.append("- `").append(f.file()).append(':').append(f.line()).append('`');
				if (!f.anchor().isEmpty()) {
					sb.append(" `#").append(f.anchor()).append('`');
				}
				sb.append(" — `").append(f.snippet().replace("`", "ˋ")).append("`\n");
			}
		}
		return sb.toString();
	}

	private static void row(StringBuilder sb, String id, List<SqlLegacyScanner.Finding> list, String hint) {
		int n = list == null ? 0 : list.size();
		long files = list == null ? 0 : list.stream().map(SqlLegacyScanner.Finding::file).distinct().count();
		sb.append("| ").append(id).append(" | ").append(n).append(" | ").append(files).append(" | ").append(hint)
				.append(" |\n");
	}

	static Path repoRoot() {
		Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (current != null) {
			if (Files.exists(current.resolve(".git"))) {
				return current;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("找不到仓库根（向上未发现 .git）");
	}
}
