package com.zhiflow.common.test.spike;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 档 2 可行性验证 A：把真实 pg_dump 直接喂给 H2，看要改多少处。
 *
 * <p><b>这不是一个会长期存在的测试，是一次实验的可复现记录。</b>
 *
 * <p>验证对象是 `zhiflow_mall_1` 三张有代表性的表：
 * `coupon_user`（基准）、`goods_spu`（多列索引 + 部分唯一索引）、`page_devise`（部分唯一索引）。
 * 这三张跑不通，`BaseDbUnitTest` 走 H2 这条路就不成立。
 */
class H2DialectSpikeTest {

	@Test
	@DisplayName("轮次 1：pg_dump 原样喂给 H2")
	void round1_rawPgDump() throws Exception {
		report("轮次 1 · 原样", run(loadStatements("/sql/pg-schema-sample.sql"), false));
	}

	@Test
	@DisplayName("轮次 2：做机械方言转换后再喂，看剩下哪些绕不过去")
	void round2_afterMechanicalTranslation() throws Exception {
		report("轮次 2 · 机械转换后", run(loadStatements("/sql/pg-schema-sample.sql"), true));
	}

	private record Result(int total, int ok, List<String> failures) {
	}

	private Result run(List<String> statements, boolean translate) throws SQLException {
		List<String> failures = new ArrayList<>();
		int ok = 0;
		// MODE=PostgreSQL 是 H2 的 PG 兼容模式；DATABASE_TO_LOWER 对齐 PG 的标识符大小写语义
		String url = "jdbc:h2:mem:spike_" + (translate ? "t" : "raw")
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

		try (Connection connection = DriverManager.getConnection(url, "sa", "");
				Statement statement = connection.createStatement()) {
			// pg_dump 的对象都带 schema 前缀，H2 里得先把 schema 建出来
			statement.execute("CREATE SCHEMA IF NOT EXISTS zhiflow_mall_1");

			for (String raw : statements) {
				String sql = translate ? translate(raw) : raw;
				try {
					statement.execute(sql);
					ok++;
				}
				catch (SQLException e) {
					failures.add("%s%n    ↳ %s".formatted(firstLine(raw), e.getMessage().split("\n")[0]));
				}
			}
		}
		return new Result(statements.size(), ok, failures);
	}

	/**
	 * 能机械替换掉的 PG 方言。
	 *
	 * <p>每一条都是「无脑字符串替换」——如果 H2 路线只需要这些，那它就是可自动化的、可接受的。
	 * 真正的判据是：做完这些之后<b>还剩什么</b>。
	 */
	private static String translate(String sql) {
		return sql
				// PG 的 char(n) 内部类型名，H2 不认
				.replace("::bpchar", "")
				.replace("::character varying", "")
				.replace("::text", "")
				.replace("::numeric", "")
				// pg_dump 特有的 ONLY 关键字
				.replace("ALTER TABLE ONLY ", "ALTER TABLE ")
				// 索引方法声明
				.replace(" USING btree ", " ");
	}

	private static void report(String label, Result result) {
		System.out.println("""

				══════════ H2 方言验证 · %s ══════════
				语句总数 : %d
				成功     : %d
				失败     : %d
				%s
				════════════════════════════════════════════
				""".formatted(label, result.total(), result.ok(), result.failures().size(),
				result.failures().isEmpty() ? "" : "\n失败明细：\n  " + String.join("\n  ", result.failures())));
		// 不做断言——本测试的产出是上面那份报告，不是通过/失败
	}

	/**
	 * pg_dump 里 SET / COMMENT ON 这类与建表无关的语句直接跳过，只留结构性 DDL。
	 * 包级可见：{@link PostgresContainerSpikeTest} 复用同一份语句，两边才是同一把尺子。
	 */
	static List<String> loadStatements(String resource) throws IOException {
		String raw;
		try (InputStream in = H2DialectSpikeTest.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("找不到 " + resource);
			}
			raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		String withoutComments = raw.lines()
				.filter(line -> !line.startsWith("--"))
				.reduce("", (a, b) -> a + "\n" + b);

		return Arrays.stream(withoutComments.split(";"))
				.map(String::trim)
				.filter(s -> !s.isBlank())
				.filter(s -> {
					String upper = s.toUpperCase();
					return upper.startsWith("CREATE TABLE")
							|| upper.startsWith("CREATE INDEX")
							|| upper.startsWith("CREATE UNIQUE INDEX")
							|| upper.startsWith("ALTER TABLE");
				})
				.toList();
	}

	private static String firstLine(String sql) {
		String line = sql.lines().findFirst().orElse(sql);
		return line.length() > 110 ? line.substring(0, 110) + "…" : line;
	}
}
