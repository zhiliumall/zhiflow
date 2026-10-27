package com.zhiflow.common.data.ddl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CARD-01 真库验证：提升过的身份<b>不留在连接池里</b>。
 *
 * <p>需要先在一次性本地实例上跑过 {@code ops/db/accounts/test/run-local.sh}（建好角色与口令），默认跳过：
 * <pre>
 * ./mvnw test -pl zhiflow-common/zhiflow-common-data -Dtest=DdlExecutionRolePgTest \
 *   -Dddlrole.pg.url=jdbc:postgresql://127.0.0.1:55432/zhiflow -Dddlrole.pg.password=localtest-app_mall_admin-0123456789
 * </pre>
 * 连接池故意只开 1 条连接，下一次借出的必然是同一条物理连接——身份有没有残留一眼可见。
 */
@EnabledIfSystemProperty(named = "ddlrole.pg.url", matches = ".+")
class DdlExecutionRolePgTest {

	private static final String URL = System.getProperty("ddlrole.pg.url");

	private static final String USER = System.getProperty("ddlrole.pg.user", "app_mall_admin");

	private static final String PASSWORD = System.getProperty("ddlrole.pg.password", "");

	private static HikariDataSource onePool() {
		HikariConfig cfg = new HikariConfig();
		cfg.setJdbcUrl(URL);
		cfg.setUsername(USER);
		cfg.setPassword(PASSWORD);
		cfg.setMaximumPoolSize(1);
		cfg.setMinimumIdle(1);
		return new HikariDataSource(cfg);
	}

	private static DdlExecutionRole enabled(DataSource pool) {
		return new DdlExecutionRole(new MockEnvironment().withProperty(DdlExecutionRole.ENABLED_PROPERTY, "true"),
				() -> new DdlExecutionRole.ConnectionSpec(URL, USER, PASSWORD), () -> pool);
	}

	private static String currentUser(Connection c) throws SQLException {
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT current_user")) {
			rs.next();
			return rs.getString(1);
		}
	}

	@Test
	@DisplayName("物理连接：SET ROLE 生效，且连接池从头到尾没被碰")
	void physicalConnectionSwitchesRoleWithoutTouchingPool() throws SQLException {
		try (HikariDataSource pool = onePool()) {
			DdlExecutionRole role = enabled(pool);
			try (Connection ddl = role.dataSourceFor(pool).getConnection()) {
				assertThat(currentUser(ddl)).isEqualTo("gp_migrator");
				try (Statement st = ddl.createStatement()) {
					st.execute("CREATE TEMP TABLE ddl_probe(id int)");
				}
			}
			try (Connection pooled = pool.getConnection()) {
				assertThat(currentUser(pooled)).isEqualTo(USER);
			}
		}
	}

	@Test
	@DisplayName("事务内 SET LOCAL ROLE：事务里是 gp_migrator，回池后再借出来是应用账号")
	void localRoleResetsAtCommit() throws SQLException {
		try (HikariDataSource pool = onePool()) {
			DdlExecutionRole role = enabled(pool);
			try (Connection c = pool.getConnection()) {
				c.setAutoCommit(false);
				role.setLocalRole(c);
				assertThat(currentUser(c)).isEqualTo("gp_migrator");
				c.commit();
				c.setAutoCommit(true);
			}
			try (Connection again = pool.getConnection()) {
				assertThat(currentUser(again)).isEqualTo(USER);
			}
			try (Connection c = pool.getConnection()) {
				c.setAutoCommit(false);
				role.setLocalRole(c);
				c.rollback();
				c.setAutoCommit(true);
			}
			try (Connection again = pool.getConnection()) {
				assertThat(currentUser(again)).as("回滚也要复原").isEqualTo(USER);
			}
		}
	}

	@Test
	@DisplayName("对照：如果在池连接上用会话级 SET ROLE，下一个借到它的请求就带着 DDL 身份——这正是本类不这么做的原因")
	void controlSessionLevelSetRoleLeaksThroughPool() throws SQLException {
		try (HikariDataSource pool = onePool()) {
			try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
				st.execute("SET ROLE gp_migrator");
			}
			try (Connection next = pool.getConnection()) {
				assertThat(currentUser(next)).isEqualTo("gp_migrator");
				try (Statement st = next.createStatement()) {
					st.execute("RESET ROLE");
				}
			}
		}
	}
}
