/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.data.ddl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 运行时 DDL 的执行身份（压测后未完成项 CARD-01「数据库专用账号」）。
 *
 * <h2>为什么要有它</h2>
 * 现在所有服务都用超级账号 {@code postgres} 连库，运行时 DDL（mall-admin / im 启动迁移、开租户建 schema 与建表、
 * IM 向量表）从来不缺权限。切到应用专用账号（{@code app_*}，只有增删改查）以后，这些 DDL 会当场
 * {@code permission denied}。做法：应用账号是 {@code gp_migrator} 的成员但 {@code INHERIT FALSE, SET TRUE}——
 * 平时会话没有 DDL 权限，只有 DDL 代码路径显式切换到 {@code gp_migrator} 才有，且建出来的对象属主统一是
 * {@code gp_migrator}（账号脚本里的默认授权会自动把增删改查授给应用账号）。
 *
 * <h2>开关</h2>
 * {@value #ENABLED_PROPERTY}，默认关；值缺失或写错一律按关处理（只告警一次）。<b>关时本类什么都不做</b>：
 * {@link #dataSourceFor} 原样返回传入的连接池，{@link #setLocalRole} 不发任何语句，行为与改造前逐字节一致。
 * 每次调用现读 {@link Environment}。
 * <p>
 * 例外：{@link #migrationDataSource()} / {@link #unpooledDataSource()} 开关关时也不经连接池（只是不切身份）——
 * Flyway 会改连接的 {@code search_path} 且关闭时复原不完整，绝不能借业务池的连接（10-18 租户隔离修复）。
 *
 * <h2>两种切换方式（都不会把提升过的身份留在连接池里）</h2>
 * <ul>
 *   <li>{@link #dataSourceFor}：给自动提交的 DDL 用（Flyway 改用 {@link #migrationDataSource()}）。开关开时每次新开一条<b>物理连接</b>
 *       （不经连接池）并 {@code SET ROLE}，用完物理关闭。Flyway 一次迁移要拿好几次连接、跨多个事务，
 *       只能这样；连接池里的连接从不被 {@code SET ROLE}。</li>
 *   <li>{@link #setLocalRole} / {@link #setLocalRoleInCurrentTransaction()}：给已经在事务里的 DDL
 *       （MyBatis mapper 里的建表、开租户快照）用，发 {@code SET LOCAL ROLE}——事务结束（提交或回滚）
 *       PG 自动复原，连接回池时已是原账号。不在事务里调用直接抛异常：PG 对事务外的 {@code SET LOCAL}
 *       只给一条 WARNING 然后什么都不做，那会让 DDL 静默地以应用账号执行、当场权限不足。</li>
 * </ul>
 */
@Slf4j
public class DdlExecutionRole {

	/** 总开关，默认 false。各服务放在自己 dataId 已有的 {@code zhiflow.perf} 块下。 */
	public static final String ENABLED_PROPERTY = "zhiflow.perf.db-ddl-set-role.enabled";

	/** 切换到的角色名，默认 {@value #DEFAULT_ROLE}。 */
	public static final String ROLE_PROPERTY = "zhiflow.perf.db-ddl-set-role.role";

	public static final String DEFAULT_ROLE = "gp_migrator";

	/** 角色名要拼进 SQL：只收小写标识符。 */
	private static final Pattern ROLE_NAME = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

	private final Environment environment;

	private final Supplier<ConnectionSpec> connectionSpec;

	private final Supplier<DataSource> applicationDataSource;

	private final ConnectionOpener opener;

	private final AtomicBoolean badFlagWarned = new AtomicBoolean();

	private final AtomicBoolean badRoleWarned = new AtomicBoolean();

	/**
	 * 物理连接的连接信息。
	 *
	 * @param url      JDBC URL（含 currentSchema / ApplicationName / options 等，与连接池同一份）
	 * @param username 登录账号
	 * @param password 口令
	 */
	public record ConnectionSpec(String url, String username, String password) {

		@Override
		public String toString() {
			// 口令不进日志
			return "ConnectionSpec[url=" + url + ", username=" + username + "]";
		}
	}

	/** 开一条物理连接。测试注入假实现；生产走 {@link DriverManager}。 */
	@FunctionalInterface
	public interface ConnectionOpener {

		Connection open(ConnectionSpec spec) throws SQLException;
	}

	public DdlExecutionRole(Environment environment, Supplier<ConnectionSpec> connectionSpec,
			Supplier<DataSource> applicationDataSource) {
		this(environment, connectionSpec, applicationDataSource, DdlExecutionRole::openWithDriverManager);
	}

	public DdlExecutionRole(Environment environment, Supplier<ConnectionSpec> connectionSpec,
			Supplier<DataSource> applicationDataSource, ConnectionOpener opener) {
		this.environment = environment;
		this.connectionSpec = connectionSpec;
		this.applicationDataSource = applicationDataSource;
		this.opener = opener;
	}

	/**
	 * 永远关闭的实例：单测里手工 new 业务类时用。{@link #dataSourceFor} / {@link #setLocalRole} 等价于没有本组件；
	 * 但它没有连接信息，{@link #migrationDataSource()} / {@link #unpooledDataSource()} 直接抛异常（迁移绝不回落到连接池）。
	 */
	public static DdlExecutionRole disabled() {
		return new DdlExecutionRole(null, () -> {
			throw new IllegalStateException("disabled");
		}, () -> null);
	}

	public boolean isEnabled() {
		if (environment == null) {
			return false;
		}
		try {
			return Boolean.TRUE.equals(environment.getProperty(ENABLED_PROPERTY, Boolean.class, Boolean.FALSE));
		}
		catch (RuntimeException ex) {
			if (badFlagWarned.compareAndSet(false, true)) {
				log.warn("{} 的值无法解析为布尔，按关闭处理: {}", ENABLED_PROPERTY, ex.toString());
			}
			return false;
		}
	}

	/** 要切换到的角色名；配置非法时回落默认值并告警一次（不拼非法标识符进 SQL）。 */
	public String role() {
		String configured = environment == null ? null : environment.getProperty(ROLE_PROPERTY);
		if (configured == null || configured.isBlank()) {
			return DEFAULT_ROLE;
		}
		String trimmed = configured.trim();
		if (!ROLE_NAME.matcher(trimmed).matches()) {
			if (badRoleWarned.compareAndSet(false, true)) {
				log.warn("{}={} 不是合法的小写角色名，按默认 {} 处理", ROLE_PROPERTY, trimmed, DEFAULT_ROLE);
			}
			return DEFAULT_ROLE;
		}
		return trimmed;
	}

	/**
	 * Flyway 与自动提交的 DDL 用的数据源。
	 *
	 * @param pooled 业务连接池（开关关时原样返回）
	 * @return 开关关：{@code pooled}；开关开：每次 {@code getConnection()} 新开物理连接并 {@code SET ROLE}，
	 * 关闭即物理关闭、不回池
	 */
	public DataSource dataSourceFor(DataSource pooled) {
		if (!isEnabled()) {
			return pooled;
		}
		return new RoleSwitchingDataSource(connectionSpec.get(), role(), opener);
	}

	/**
	 * Flyway 用的数据源：<b>不论开关开或关，都不经业务连接池</b>（10-18 lo-flyway-pool-schema，租户隔离）。
	 *
	 * <p>为什么不能用业务池：Flyway 的 {@code initSql("SET search_path TO <租户>, public")} 在拿到连接后先执行，
	 * Flyway 之后才记录「原始 search_path」，关闭时只复原到 initSql <b>之后</b>的值——连接带着别的租户 schema 回池。
	 * {@code SchemaInterceptor} 的按物理连接记忆（{@code zhiflow.schema-interceptor.memoize}）不知道这次改动，
	 * 下一条命中旧记忆的业务语句会跳过 SET、在别的租户 schema 里读写。迁移脚本里没进事务的会话级设置同理。
	 * 物理连接用完即物理关闭，池里的连接从不被 Flyway 碰到。
	 *
	 * @return 开关开：同 {@link #dataSourceFor}（物理连接 + {@code SET ROLE}）；开关关：{@link #unpooledDataSource()}
	 * @throws IllegalStateException 取不到连接信息（没有动态数据源配置等）——调用方按迁移失败处理，绝不回落到业务池
	 */
	public DataSource migrationDataSource() {
		if (isEnabled()) {
			return new RoleSwitchingDataSource(connectionSpec.get(), role(), opener);
		}
		return unpooledDataSource();
	}

	/**
	 * 不经业务连接池、不切身份的数据源：每次 {@code getConnection()} 用业务池同一份 URL / 账号 / 口令新开物理连接，
	 * 关闭即物理关闭。给只读的 Flyway {@code info()} 这类「要改会话状态、但不需要 DDL 权限」的调用用。
	 *
	 * @throws IllegalStateException 取不到连接信息
	 */
	public DataSource unpooledDataSource() {
		return new UnpooledDataSource(connectionSpec.get(), opener);
	}

	/**
	 * 在调用方已开启的事务里切换身份（{@code SET LOCAL ROLE}），事务结束自动复原。开关关时什么都不做。
	 *
	 * @throws IllegalStateException 开关开但连接处于自动提交（不在事务里）
	 */
	public void setLocalRole(Connection connection) throws SQLException {
		if (!isEnabled()) {
			return;
		}
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("SET LOCAL ROLE 必须在事务里执行（连接处于自动提交）："
					+ "PG 对事务外的 SET LOCAL 只告警不生效，DDL 会以应用账号执行并权限不足");
		}
		try (Statement st = connection.createStatement()) {
			st.execute("SET LOCAL ROLE " + role());
		}
	}

	/**
	 * Spring 声明式事务里（MyBatis mapper 的 DDL）切换身份：取当前事务绑定的连接发 {@code SET LOCAL ROLE}。
	 * 开关关时什么都不做。
	 *
	 * @throws IllegalStateException 开关开但当前没有实际事务
	 */
	public void setLocalRoleInCurrentTransaction() {
		if (!isEnabled()) {
			return;
		}
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("SET LOCAL ROLE 必须在 Spring 事务里调用（当前没有实际事务）");
		}
		DataSource ds = applicationDataSource.get();
		if (ds == null) {
			throw new IllegalStateException("找不到应用数据源，无法切换 DDL 身份");
		}
		Connection c = DataSourceUtils.getConnection(ds);
		try {
			setLocalRole(c);
		}
		catch (SQLException e) {
			throw new IllegalStateException("切换 DDL 身份失败: SET LOCAL ROLE " + role(), e);
		}
		finally {
			DataSourceUtils.releaseConnection(c, ds);
		}
	}

	private static Connection openWithDriverManager(ConnectionSpec spec) throws SQLException {
		Properties props = new Properties();
		if (spec.username() != null) {
			props.setProperty("user", spec.username());
		}
		if (spec.password() != null) {
			props.setProperty("password", spec.password());
		}
		return DriverManager.getConnection(spec.url(), props);
	}

	/** 每次新开物理连接并 {@code SET ROLE} 的数据源；连接关闭即物理关闭。 */
	static final class RoleSwitchingDataSource extends AbstractDataSource {

		private final ConnectionSpec spec;

		private final String role;

		private final ConnectionOpener opener;

		RoleSwitchingDataSource(ConnectionSpec spec, String role, ConnectionOpener opener) {
			this.spec = spec;
			this.role = role;
			this.opener = opener;
		}

		@Override
		public Connection getConnection() throws SQLException {
			Connection c = opener.open(spec);
			try (Statement st = c.createStatement()) {
				st.execute("SET ROLE " + role);
			}
			catch (SQLException | RuntimeException e) {
				try {
					c.close();
				}
				catch (SQLException closeEx) {
					e.addSuppressed(closeEx);
				}
				throw e;
			}
			return c;
		}

		@Override
		public Connection getConnection(String username, String password) {
			throw new UnsupportedOperationException("DDL 物理连接只用配置里的账号");
		}

		String role() {
			return role;
		}

		ConnectionSpec spec() {
			return spec;
		}
	}

	/** 每次新开物理连接、不切身份的数据源；连接关闭即物理关闭。 */
	static final class UnpooledDataSource extends AbstractDataSource {

		private final ConnectionSpec spec;

		private final ConnectionOpener opener;

		UnpooledDataSource(ConnectionSpec spec, ConnectionOpener opener) {
			this.spec = spec;
			this.opener = opener;
		}

		@Override
		public Connection getConnection() throws SQLException {
			return opener.open(spec);
		}

		@Override
		public Connection getConnection(String username, String password) {
			throw new UnsupportedOperationException("迁移物理连接只用配置里的账号");
		}

		ConnectionSpec spec() {
			return spec;
		}
	}
}
