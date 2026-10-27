package com.zhiflow.common.data.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * MyBatis-Plus 解析型拦截器的 SQL 重写契约测试（黄金文件对比）。
 *
 * <p><b>为什么需要它</b>：本仓把 {@code jsqlparser} 钉在 dependencyManagement 里，
 * 而 MP 的 {@code mybatis-plus-jsqlparser} 模块有自己的编译期绑定版本。两者一旦错开，
 * 失败形态是<b>运行时</b> {@code NoSuchMethodError} —— 编译通过、单测通过、一上生产就 500。
 * 2026-08-06 的一次报错就是这么来的：jsqlparser 钉 5.0，
 * 而 {@code TenantLineInnerInterceptor.lambda$processSelect$0} 直接调 5.2 才有的
 * {@code WithItem.getSelect()}，于是任何含 CTE 的 SQL 必炸。
 *
 * <p><b>它守什么</b>：把一组覆盖本仓真实在用的 PG 构造的 SQL 喂给拦截器，
 * 把重写结果固化成黄金文件。以后任何人改动 jsqlparser 版本、MP 版本或租户处理器，
 * 只要重写结果变了，这个测试就会红 —— 而不是等到生产 500。
 *
 * <p><b>样本集的选取依据</b>（不是凭想象挑的，是实测 552 个 mapper XML 的构造分布）：
 * {@code ::} 19 个文件、{@code FILTER (WHERE)} 12 个、{@code LATERAL} 7 个、
 * {@code OVER()} 2 个、{@code @>} 2 个、{@code ILIKE} 1 个、{@code ON CONFLICT} 1 个。
 * 命中 0 次的构造（{@code ->>}、{@code DISTINCT ON}、{@code ARRAY[}、{@code RETURNING}）
 * 不纳入 —— 不为不存在的用法写守卫。
 *
 * <p><b>黄金文件缺失时的行为</b>：自动写入并通过，同时打印提示。
 * 这样首次引入（或刻意重建基线）不会卡住，但之后每次都会断言。
 *
 * @see com.zhiflow.common.data.tenant.BaseTenantHandler 生产用的租户处理器
 */
@DisplayName("MyBatis-Plus SQL 重写契约（jsqlparser 版本护栏）")
class SqlRewriteContractTest {

	/** 黄金文件。跑测试的工作目录是模块 basedir，所以用相对路径。 */
	private static final Path GOLDEN = Paths.get("src/test/resources/sql-rewrite-golden.txt");

	/** 实际输出。失败时用它和黄金文件 diff。 */
	private static final Path ACTUAL = Paths.get("target/sql-rewrite-actual.txt");

	/**
	 * 租户表清单。生产里由配置中心的 {@code base.tenant.tables} 下发，这里取几张有代表性的。
	 * {@code sys_config} 刻意不在表内，用来验 {@code ignoreTable} 的「不注入」分支 —— 它是对照样本：
	 * 如果连它都被注入了 tenant_id，说明 handler 压根没被调用，整个测试的判据就是坏的。
	 */
	private static final Set<String> TENANT_TABLES =
			Set.of("order_info", "goods_spu", "coupon_user", "user_footprint");

	/**
	 * 与生产 {@code BaseTenantHandler} 行为对齐的 stub：租户值用 {@link StringValue}
	 * （即注入 {@code tenant_id = '1'} 字符串字面量，不是数字 —— 这点必须一致，
	 * 否则黄金文件固化的就不是生产形态）。
	 */
	private static final class StubTenantHandler implements TenantLineHandler {
		@Override
		public Expression getTenantId() {
			return new StringValue("1");
		}

		@Override
		public String getTenantIdColumn() {
			return "tenant_id";
		}

		@Override
		public boolean ignoreTable(String tableName) {
			return !TENANT_TABLES.contains(tableName);
		}
	}

	/** 租户注入样本。key 是用例名（稳定，用于 diff 定位），value 是输入 SQL。 */
	private static Map<String, String> tenantSamples() {
		Map<String, String> m = new LinkedHashMap<>();
		// --- 基础形态 ---
		m.put("01-select-tenant-table", "SELECT id, total FROM order_info WHERE del_flag = '0'");
		m.put("02-select-NON-tenant-table-CONTROL", "SELECT id FROM sys_config WHERE k = 'x'");
		m.put("03-join-two-tenant-tables",
				"SELECT o.id, g.name FROM order_info o JOIN goods_spu g ON g.id = o.spu_id");
		m.put("04-subquery-in",
				"SELECT id FROM order_info WHERE user_id IN "
						+ "(SELECT user_id FROM user_footprint WHERE del_flag = '0')");
		// --- PG 特有构造（按实测分布挑） ---
		m.put("05-pg-cast", "SELECT id, (amount)::numeric(10,2) AS amt FROM order_info");
		m.put("06-pg-filter-where",
				"SELECT count(*) FILTER (WHERE status = '1') AS paid, count(*) AS total FROM coupon_user");
		m.put("07-pg-lateral",
				"SELECT o.id, x.n FROM order_info o, LATERAL "
						+ "(SELECT count(*) AS n FROM coupon_user c WHERE c.user_id = o.user_id) x");
		m.put("08-pg-window-over",
				"SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY create_time DESC) AS rn "
						+ "FROM order_info");
		m.put("09-pg-jsonb-contains", "SELECT id FROM goods_spu WHERE extended @> '{\"a\":1}'");
		m.put("10-pg-ilike", "SELECT id FROM goods_spu WHERE name ILIKE '%vitamin%'");
		// --- 写操作 ---
		m.put("11-update", "UPDATE order_info SET status = '2' WHERE id = 1");
		m.put("12-delete", "DELETE FROM coupon_user WHERE id = 1");
		m.put("13-insert", "INSERT INTO coupon_user (id, user_id) VALUES (1, 2)");
		m.put("14-pg-insert-on-conflict",
				"INSERT INTO coupon_user (id, user_id) VALUES (1, 2) ON CONFLICT (id) DO NOTHING");
		// --- 本任务的主角：CTE ---
		// jsqlparser 5.0 下这条必抛 NoSuchMethodError: WithItem.getSelect()；5.2 下应正常注入。
		m.put("15-CTE-the-defect",
				"WITH alive AS (SELECT user_id FROM user_footprint WHERE del_flag = '0') "
						+ "SELECT o.id FROM order_info o WHERE o.user_id IN (SELECT user_id FROM alive)");
		m.put("16-CTE-recursive",
				"WITH RECURSIVE t AS (SELECT id, pid FROM goods_spu WHERE pid IS NULL "
						+ "UNION ALL SELECT g.id, g.pid FROM goods_spu g JOIN t ON g.pid = t.id) "
						+ "SELECT id FROM t");
		return m;
	}

	/** 分页 count SQL 样本。 */
	private static Map<String, String> countSamples() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("C1-simple", "SELECT id, total FROM order_info WHERE del_flag = '0'");
		m.put("C2-join", "SELECT o.id FROM order_info o JOIN goods_spu g ON g.id = o.spu_id");
		m.put("C3-order-by", "SELECT id FROM order_info ORDER BY create_time DESC");
		m.put("C4-group-by", "SELECT user_id, count(*) FROM order_info GROUP BY user_id");
		m.put("C5-CTE",
				"WITH alive AS (SELECT user_id FROM user_footprint WHERE del_flag = '0') "
						+ "SELECT o.id FROM order_info o WHERE o.user_id IN (SELECT user_id FROM alive)");
		return m;
	}

	@Test
	@DisplayName("租户注入 + 分页 count 的重写结果与黄金文件一致")
	void rewriteOutput_matchesGolden() throws IOException {
		TenantLineInnerInterceptor tenant = new TenantLineInnerInterceptor(new StubTenantHandler());
		PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);

		StringBuilder out = new StringBuilder();
		out.append("# MyBatis-Plus SQL 重写契约黄金文件\n")
				.append("# 由 SqlRewriteContractTest 生成。不要手改 —— 要改就跑测试重新生成并 review diff。\n")
				.append("# 格式：用例名 | IN  => 输入SQL\n")
				.append("#       用例名 | OUT => 重写结果，或 THROWN: <异常类名>: <消息>\n")
				.append("\n=== TenantLineInnerInterceptor ===\n");

		tenantSamples().forEach((name, sql) -> {
			out.append(name).append(" | IN  => ").append(sql).append('\n');
			out.append(name).append(" | OUT => ").append(rewriteQuietly(() -> tenant.parserSingle(sql, null)))
					.append('\n');
		});

		out.append("\n=== PaginationInnerInterceptor.autoCountSql ===\n");
		countSamples().forEach((name, sql) -> {
			out.append(name).append(" | IN  => ").append(sql).append('\n');
			out.append(name).append(" | OUT => ")
					.append(rewriteQuietly(() -> pagination.autoCountSql(new Page<>(1, 10), sql)))
					.append('\n');
		});

		String actual = out.toString();
		Files.createDirectories(ACTUAL.getParent());
		Files.writeString(ACTUAL, actual, StandardCharsets.UTF_8);

		if (!Files.exists(GOLDEN)) {
			Files.createDirectories(GOLDEN.getParent());
			Files.writeString(GOLDEN, actual, StandardCharsets.UTF_8);
			System.out.println("[SqlRewriteContractTest] 黄金文件不存在，已按当前实际输出生成：" + GOLDEN.toAbsolutePath()
					+ "\n  ⚠ 请人工 review 这份基线是否就是你想固化的形态，再提交。");
			return;
		}

		String golden = Files.readString(GOLDEN, StandardCharsets.UTF_8);
		if (!golden.equals(actual)) {
			// 把差异的前几行直接打出来，免得还要去翻文件
			List<String> g = golden.lines().toList();
			List<String> a = actual.lines().toList();
			StringBuilder diff = new StringBuilder();
			int max = Math.max(g.size(), a.size());
			int shown = 0;
			for (int i = 0; i < max && shown < 20; i++) {
				String gl = i < g.size() ? g.get(i) : "<缺行>";
				String al = i < a.size() ? a.get(i) : "<缺行>";
				if (!gl.equals(al)) {
					diff.append("  line ").append(i + 1).append("\n    golden: ").append(gl)
							.append("\n    actual: ").append(al).append('\n');
					shown++;
				}
			}
			fail("SQL 重写结果与黄金文件不一致。这通常意味着 jsqlparser 或 MyBatis-Plus 的版本变了，"
					+ "而变化会直接作用在多租户隔离和分页上 —— 逐条确认是修正还是回归，不要直接重建基线。\n"
					+ "完整实际输出：" + ACTUAL.toAbsolutePath() + "\n前 20 处差异：\n" + diff);
		}
		assertEquals(golden, actual);
	}

	/**
	 * 跑一次重写，把结果或抛出的 Throwable 都转成可固化的单行字符串。
	 *
	 * <p>必须捕 {@link Throwable} 而不是 {@link Exception}：本任务要固化的
	 * {@code NoSuchMethodError} 是 {@link Error}，catch Exception 抓不到。
	 */
	private static String rewriteQuietly(ThrowingSupplier op) {
		try {
			String r = op.get();
			return r == null ? "<null>" : r.replace('\n', ' ').replaceAll(" +", " ").trim();
		}
		catch (Throwable t) {
			String msg = t.getMessage() == null ? "" : t.getMessage().replace('\n', ' ');
			return "THROWN: " + t.getClass().getName() + ": " + msg;
		}
	}

	@FunctionalInterface
	private interface ThrowingSupplier {
		String get() throws Throwable;
	}
}
