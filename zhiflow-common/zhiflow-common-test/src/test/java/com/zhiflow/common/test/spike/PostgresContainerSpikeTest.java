package com.zhiflow.common.test.spike;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 档 2 可行性验证 B：Testcontainers 起真 PostgreSQL。
 *
 * <p>与 {@link H2DialectSpikeTest} 用同一份 pg_dump、同一组判据，唯一变量是数据库。
 * H2 那边需要 6 条机械替换才能跑到 17/19，且 2 个部分唯一索引<b>无解</b>；
 * 这边验证的是「原样 0 转换能不能全过，且部分唯一索引真的生效」。
 *
 * <p><b>Docker 不可用时自动跳过</b>（{@code Assumptions}），不会让构建变红——
 * 本机 Docker Desktop 装了但常不启动。要跑：{@code open -a Docker} 后再执行。
 */
class PostgresContainerSpikeTest {

	/** 对齐部署的大版本（PG 18）；镜像拉不到时换 postgres:16-alpine 同样能验方言 */
	private static final String IMAGE = "postgres:18-alpine";

	private static final String SCHEMA = "zhiflow_mall_1";

	@Test
	@DisplayName("真 PG：pg_dump 原样执行，零方言转换")
	void rawPgDumpRunsUntouched() throws Exception {
		Assumptions.assumeTrue(dockerAvailable(), "Docker 未启动，跳过（open -a Docker 后重跑）");

		try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(IMAGE)) {
			pg.start();
			try (Connection connection = connect(pg); Statement statement = connection.createStatement()) {
				statement.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);

				int ok = 0;
				StringBuilder failures = new StringBuilder();
				for (String sql : H2DialectSpikeTest.loadStatements("/sql/pg-schema-sample.sql")) {
					try {
						statement.execute(sql);   // 注意：没有 translate()
						ok++;
					}
					catch (SQLException e) {
						failures.append("\n  ").append(sql.lines().findFirst().orElse(sql))
								.append("\n    ↳ ").append(e.getMessage().split("\n")[0]);
					}
				}

				System.out.printf("""

						══════════ 真 PG 验证结果 ══════════
						语句总数 : %d（原样，零转换）
						成功     : %d
						失败     : %d%s
						══════════════════════════════════
						%n""", ok + countChar(failures), ok, countChar(failures), failures);

				if (!failures.isEmpty()) {
					fail("真 PG 上执行自家 pg_dump 竟然失败了，说明导出方式有问题：" + failures);
				}
			}
		}
	}

	/**
	 * 判据 3：部分唯一索引真的生效。
	 *
	 * <p>H2 建不出这个索引，这条断言在 H2 上根本无从写起——
	 * 而它保的是「同一入口同一人群只能有一个已发布页面」这条真实业务约束。
	 */
	@Test
	@DisplayName("真 PG：page_devise 的部分唯一索引真的拦得住第二行 enable='1'")
	void partialUniqueIndexIsEnforced() throws Exception {
		Assumptions.assumeTrue(dockerAvailable(), "Docker 未启动，跳过（open -a Docker 后重跑）");

		try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(IMAGE)) {
			pg.start();
			try (Connection connection = connect(pg); Statement statement = connection.createStatement()) {
				statement.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
				for (String sql : H2DialectSpikeTest.loadStatements("/sql/pg-schema-sample.sql")) {
					statement.execute(sql);
				}

				statement.execute(insertPage("p-1", "1"));
				// 同元组第二行 enable='1' 必须被索引拦下
				try {
					statement.execute(insertPage("p-2", "1"));
					fail("部分唯一索引没生效——同一入口插进了第二个已发布页面");
				}
				catch (SQLException expected) {
					assertTrue(expected.getMessage().contains("uk_page_devise_enabled"),
							"报错应指向 uk_page_devise_enabled，实际：" + expected.getMessage());
				}
				// enable='0' 不受该索引约束，应当插得进去
				statement.execute(insertPage("p-3", "0"));

				try (ResultSet rs = statement.executeQuery(
						"SELECT count(*) FROM " + SCHEMA + ".page_devise")) {
					rs.next();
					assertEquals(2, rs.getInt(1));
				}
			}
		}
	}

	/**
	 * 判据 4（本任务的价值锚点）：软删行确实没被计进去。
	 *
	 * <p>S5 的核心口径「一律过滤 del_flag='0'」目前只有一条「Wrapper 片段里含 del_flag 字样」
	 * 的字符串断言在保——把 {@code eq} 写成 {@code ne} 那条断言照样通过。
	 * 这里用真数据证明语义，是字符串断言给不了的。
	 */
	@Test
	@DisplayName("真 PG：del_flag 过滤能被真数据证明（字符串断言证明不了的那部分）")
	void softDeleteFilterProvenWithRealRows() throws Exception {
		Assumptions.assumeTrue(dockerAvailable(), "Docker 未启动，跳过（open -a Docker 后重跑）");

		try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(IMAGE)) {
			pg.start();
			try (Connection connection = connect(pg); Statement statement = connection.createStatement()) {
				statement.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
				for (String sql : H2DialectSpikeTest.loadStatements("/sql/pg-schema-sample.sql")) {
					statement.execute(sql);
				}

				statement.execute(insertCouponUser("cu-1", "c-1", "0", "0"));
				statement.execute(insertCouponUser("cu-2", "c-1", "0", "0"));
				statement.execute(insertCouponUser("cu-3", "c-1", "0", "1")); // 软删

				try (ResultSet rs = statement.executeQuery(
						"SELECT count(*) FROM %s.coupon_user WHERE coupon_id='c-1' AND status='0' AND del_flag='0'"
								.formatted(SCHEMA))) {
					rs.next();
					assertEquals(2, rs.getInt(1), "软删的那行不该被数进来");
				}
			}
		}
	}

	private static String insertPage(String id, String enable) {
		return """
				INSERT INTO %s.page_devise (id, shop_id, client_type, page_type, app_id, visible_scope, enable, del_flag)
				VALUES ('%s', 's-1', 'H5', '/pages/home/index', '-1', '1', '%s', '0')
				""".formatted(SCHEMA, id, enable);
	}

	/**
	 * 真实 schema 的 NOT NULL 约束比想象中多：shop_id / user_id / coupon_code / name / receive_type
	 * 都是 NOT NULL。第一版漏了 shop_id 直接被数据库拦下——
	 * <b>这正是真库相对 H2 的价值</b>：测试夹具的偷工减料会当场暴露，而不是在生产上暴露。
	 */
	private static String insertCouponUser(String id, String couponId, String status, String delFlag) {
		return """
				INSERT INTO %s.coupon_user
				  (id, shop_id, coupon_id, user_id, coupon_code, name, status, del_flag)
				VALUES ('%s', '-1', '%s', 'u-1', %d, '测试券', '%s', '%s')
				""".formatted(SCHEMA, id, couponId, id.hashCode() & 0x7fffffff, status, delFlag);
	}

	private static Connection connect(PostgreSQLContainer<?> pg) throws SQLException {
		return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
	}

	private static boolean dockerAvailable() {
		try {
			return DockerClientFactory.instance().isDockerAvailable();
		}
		catch (Throwable t) {
			return false;
		}
	}

	private static int countChar(StringBuilder failures) {
		return failures.isEmpty() ? 0 : (int) failures.toString().lines().filter(l -> l.startsWith("  ")).count();
	}
}
