package com.zhiflow.mall.admin.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zhiflow.common.data.ddl.DdlExecutionRole;
import com.zhiflow.common.data.mybatis.SchemaInterceptor;
import com.zhiflow.common.data.shardingdatabase.config.ShardingDataBaseProperties;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 10-18 租户隔离：mall-admin 的 Flyway 迁移不能借业务连接池（lo-flyway-pool-schema）。
 *
 * <p>事故机理：Flyway 的 {@code initSql("SET search_path TO <schema>, public")} 在拿到连接后先执行，之后才记录「原始值」，
 * 关闭时只复原到 initSql 之后 ⇒ 连接带着被迁移的 schema 回池。{@code SchemaInterceptor} 开着按物理连接记忆
 * （{@code zhiflow.schema-interceptor.memoize: true}）时，下一条命中旧记忆的业务语句跳过 SET，落进别的租户 schema。
 *
 * <p>判据是<b>数据实际落在哪个 schema</b>：每个 schema 的 {@code lofw_probe} 只有一行、内容是自己的 schema 名；
 * 业务语句 {@code INSERT INTO lofw_landing … SELECT owner FROM lofw_probe RETURNING owner}（都不带 schema 前缀）
 * 返回它实际落在哪；最后按 schema 清点 landing 行数。另外逐条比对池里每条物理连接迁移前后的 {@code search_path}。
 *
 * <p>真 PG + 真 HikariCP（开源分支单数据源用的连接池）+ 真 Flyway 11 + 生产同一套 {@link MallTenantMigrator}
 * 入口与 {@link DdlExecutionRole}（开关关 = 默认配置）。未配置属性时整类跳过（surefire 记 skipped）：
 * <pre>
 * ./mvnw -o test -pl zhiflow-mall/zhiflow-mall-admin -am -Dtest=MallMigrationPoolIsolationPgTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dlofw.pg.url=jdbc:postgresql://localhost:5432/ot_flyway -Dlofw.pg.user=postgres -Dlofw.pg.password=$PW
 * </pre>
 * <b>会 DROP / 重建库里的 zhiflow_mall*、zhiflow_upms</b>，只能指向一次性的测试库。
 */
class MallMigrationPoolIsolationPgTest {

	private static final String URL = System.getProperty("lofw.pg.url", "");

	private static final String USER = System.getProperty("lofw.pg.user", "postgres");

	private static final String PASSWORD = System.getProperty("lofw.pg.password", "");

	private static final String PREFIX = "zhiflow_mall";

	private static final String BASE = PREFIX;

	private static final String TENANT_A = "1";

	private static final String TENANT_B = "1000000000000000003";

	/** 开租户场景的新租户（不在 sys_tenant 里，只走 migrateSchema）。 */
	private static final String TENANT_C = "1900000000000000007";

	private static final String SCHEMA_A = PREFIX + "_" + TENANT_A;

	private static final String SCHEMA_B = PREFIX + "_" + TENANT_B;

	private static final String SCHEMA_C = PREFIX + "_" + TENANT_C;

	private static final String HISTORY = "flyway_schema_history_mall";

	private static final String LOCATION = "classpath:db/migration/mall";

	private static final int POOL_SIZE = 4;

	/** 业务池与迁移物理连接用不同的 ApplicationName，好在 pg_stat_activity 里分开数。 */
	private static final String POOL_APP = "lofw-pool";

	private static final String MIGRATE_APP = "lofw-migrate";

	private HikariDataSource pool;

	private DataSource admin;

	private SchemaInterceptor interceptor;

	@BeforeAll
	static void requiresRealPg() {
		Assumptions.assumeTrue(!URL.isBlank() && !PASSWORD.isBlank(),
				"未配置 -Dlofw.pg.url / -Dlofw.pg.password，跳过真 PG 租户隔离测试");
	}

	@BeforeEach
	void setUp() throws Exception {
		admin = new DriverManagerDataSource(withApp(URL, "lofw-admin"), USER, PASSWORD);
		exec("DROP SCHEMA IF EXISTS zhiflow_upms CASCADE");
		exec("CREATE SCHEMA zhiflow_upms");
		exec("CREATE TABLE zhiflow_upms.sys_tenant (id varchar(32) PRIMARY KEY, del_flag char(1) NOT NULL)");
		exec("INSERT INTO zhiflow_upms.sys_tenant VALUES ('-1', '0'), ('" + TENANT_A + "', '0'), ('" + TENANT_B
				+ "', '0')");
		for (String s : List.of(BASE, SCHEMA_A, SCHEMA_B, SCHEMA_C)) {
			exec("DROP SCHEMA IF EXISTS " + s + " CASCADE");
			exec("CREATE SCHEMA " + s);
			exec("CREATE TABLE " + s + ".lofw_probe (owner text NOT NULL)");
			exec("INSERT INTO " + s + ".lofw_probe VALUES ('" + s + "')");
			exec("CREATE TABLE " + s + ".lofw_landing (id bigserial PRIMARY KEY, tenant text NOT NULL, owner text NOT NULL)");
		}
		// 存量 schema = 常态：全部版本已应用，启动时没有待执行版本（Flyway 照样逐 schema 连一遍）
		for (String s : List.of(BASE, SCHEMA_A, SCHEMA_B)) {
			markApplied(s, null);
		}

		pool = new HikariDataSource();
		pool.setJdbcUrl(withApp(URL, POOL_APP));
		pool.setUsername(USER);
		pool.setPassword(PASSWORD);
		pool.setMinimumIdle(POOL_SIZE);
		pool.setMaximumPoolSize(POOL_SIZE);
		pool.setConnectionTimeout(30000);

		ShardingDataBaseProperties props = new ShardingDataBaseProperties();
		props.setDbname(PREFIX);
		props.setDefaultTenantId("1");
		props.setSchemaInterceptorMemoize(true);
		interceptor = new SchemaInterceptor(props);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
		if (pool != null) {
			pool.close();
		}
	}

	// ------------------------------------------------------------------ 场景

	@Test
	@DisplayName("启动 migrateAll（无待执行版本）：池里每条物理连接 search_path 不变，记忆命中的租户 A 语句仍落在 A")
	void startupMigrateAll_noPending_poolUntouched() throws Exception {
		Map<String, Long> history = new HashMap<>();
		for (String s : List.of(BASE, SCHEMA_A, SCHEMA_B)) {
			history.put(s, historyRows(s));
			assertTrue(history.get(s) > 100, s + " 应已记满全部版本");
		}
		Map<Connection, String> before = warmPool(TENANT_A);

		migrator().migrateAll();

		for (String s : List.of(BASE, SCHEMA_A, SCHEMA_B)) {
			assertEquals(history.get(s), historyRows(s), s + " 迁移历史不该有新增（无待执行版本）");
		}
		assertPoolUntouchedAndLandsIn(before, TENANT_A, SCHEMA_A);
		assertNoMigrationConnectionsLeft();
	}

	@Test
	@DisplayName("开租户 migrateSchema（真执行一个迁移）：迁移落在新 schema，池不变，记忆命中的租户 B 语句仍落在 B")
	void provisionMigrateSchema_appliesPendingInTargetSchema_poolUntouched() throws Exception {
		// 新 schema：除 V2（不带 schema 前缀建 dead_letter_message，靠 initSql 的 search_path 落位）外全部记为已应用
		markApplied(SCHEMA_C, "2");
		exec("DROP TABLE IF EXISTS " + SCHEMA_C + ".dead_letter_message");
		Map<Connection, String> before = warmPool(TENANT_B);

		MallTenantMigrator.SchemaResult r = migrator().migrateSchema(SCHEMA_C);

		assertTrue(r.ok(), "迁移应成功: " + r);
		assertEquals(1, r.applied(), "只有 V2 待执行");
		assertEquals(1, count("SELECT count(*) FROM pg_tables WHERE tablename = 'dead_letter_message' AND schemaname = '"
				+ SCHEMA_C + "'"), "initSql 在独立连接上照样生效：V2 的表建在被迁移的 schema 里");
		assertEquals(0, count("SELECT count(*) FROM pg_tables WHERE tablename = 'dead_letter_message' AND schemaname <> '"
				+ SCHEMA_C + "'"), "V2 的表不能建到别的 schema");
		assertPoolUntouchedAndLandsIn(before, TENANT_B, SCHEMA_B);
		assertNoMigrationConnectionsLeft();
	}

	@Test
	@DisplayName("迁移失败路径（空 schema 从 V2 起重放、中途失败）：池同样不变")
	void failedMigration_poolUntouched() throws Exception {
		Map<Connection, String> before = warmPool(TENANT_A);

		MallTenantMigrator.SchemaResult r = migrator().migrateSchema(SCHEMA_C);

		// 本地空 schema 缺业务表，老迁移必然中途失败；这里只关心失败后池的状态
		assertTrue(r.failed() > 0, "预期中途失败（空 schema 缺业务表）: " + r);
		assertPoolUntouchedAndLandsIn(before, TENANT_A, SCHEMA_A);
		assertNoMigrationConnectionsLeft();
	}

	@Test
	@DisplayName("启动迁移期间与之后业务语句一直在跑（A/B 两租户、自动提交 + 事务混跑）：零串租户")
	void businessTrafficDuringAndAfterStartupMigration_neverCrossesTenant() throws Exception {
		// 先把池里每条连接都记成 A（模拟实例已在接流量）
		warmPool(TENANT_A);
		long warmA = landingRows(SCHEMA_A, TENANT_A);
		AtomicBoolean stop = new AtomicBoolean();
		ConcurrentLinkedQueue<String> wrong = new ConcurrentLinkedQueue<>();
		Map<String, AtomicInteger> committed = Map.of(TENANT_A, new AtomicInteger(), TENANT_B, new AtomicInteger());
		ExecutorService exec = Executors.newFixedThreadPool(6);
		List<Future<?>> workers = new ArrayList<>();
		for (int w = 0; w < 6; w++) {
			final int worker = w;
			workers.add(exec.submit(() -> {
				int i = 0;
				while (!stop.get()) {
					// 偏重 A：池里多数连接带 A 的记忆，最容易命中陈旧记忆
					String tenant = (worker % 3 == 2 && i % 2 == 0) ? TENANT_B : TENANT_A;
					boolean inTx = i % 5 == 4;
					String expected = PREFIX + "_" + tenant;
					try (Connection c = pool.getConnection()) {
						TenantContextHolder.setTenantId(tenant);
						if (inTx) {
							c.setAutoCommit(false);
						}
						String landed = business(c, tenant);
						if (inTx) {
							c.commit();
							c.setAutoCommit(true);
						}
						committed.get(tenant).incrementAndGet();
						if (!expected.equals(landed)) {
							wrong.add("租户 " + tenant + (inTx ? "（事务）" : "") + " 落进了 " + landed);
						}
					}
					catch (SQLException e) {
						wrong.add("业务语句异常: " + e);
					}
					finally {
						TenantContextHolder.clear();
					}
					i++;
				}
				return null;
			}));
		}
		try {
			TimeUnit.MILLISECONDS.sleep(300);
			migrator().migrateAll();
			// 迁移完再跑一阵：陈旧记忆就出在迁移之后
			TimeUnit.MILLISECONDS.sleep(800);
		}
		finally {
			stop.set(true);
			for (Future<?> f : workers) {
				f.get(60, TimeUnit.SECONDS);
			}
			exec.shutdownNow();
		}

		assertTrue(wrong.isEmpty(), "出现串租户 " + wrong.size() + " 次，例如: " + wrong.stream().limit(5).toList());
		assertTrue(committed.get(TENANT_A).get() > 50 && committed.get(TENANT_B).get() > 5,
				"业务流量太少，判据无效: " + committed);
		assertEquals(warmA + committed.get(TENANT_A).get(), landingRows(SCHEMA_A, TENANT_A), "A 的写入应全部在 A");
		assertEquals(committed.get(TENANT_B).get(), landingRows(SCHEMA_B, TENANT_B), "B 的写入应全部在 B");
		assertEquals(0, count("SELECT count(*) FROM " + BASE + ".lofw_landing"), "base schema 不该有业务写入");
		assertEquals(0, count("SELECT count(*) FROM " + SCHEMA_A + ".lofw_landing WHERE tenant <> '" + TENANT_A + "'"));
		assertEquals(0, count("SELECT count(*) FROM " + SCHEMA_B + ".lofw_landing WHERE tenant <> '" + TENANT_B + "'"));
		assertNoMigrationConnectionsLeft();
	}

	// ------------------------------------------------------------------ 工具

	private MallTenantMigrator migrator() {
		MallTenantMigrator m = new MallTenantMigrator(pool);
		// 生产装配：DdlExecutionRole 由 common-data 自动配置必然注入；开关关 = 默认配置
		m.setDdlExecutionRole(ddlRole());
		return m;
	}

	/** 默认配置：zhiflow.perf.db-ddl-set-role.enabled 未配置（关）。连接信息与业务池同一个库、同一个账号。 */
	private DdlExecutionRole ddlRole() {
		return new DdlExecutionRole(new StandardEnvironment(),
				() -> new DdlExecutionRole.ConnectionSpec(withApp(URL, MIGRATE_APP), USER, PASSWORD), () -> pool);
	}

	/**
	 * 同时借出池里全部连接，逐条以 {@code tenant} 的身份过一遍拦截器（自动提交 ⇒ SET 并写记忆），记下每条物理连接的 search_path。
	 */
	private Map<Connection, String> warmPool(String tenant) throws SQLException {
		Map<Connection, String> before = new IdentityHashMap<>();
		List<Connection> held = new ArrayList<>();
		try {
			for (int i = 0; i < POOL_SIZE; i++) {
				Connection c = pool.getConnection();
				held.add(c);
				TenantContextHolder.setTenantId(tenant);
				assertEquals(PREFIX + "_" + tenant, business(c, tenant));
				before.put(c.unwrap(Connection.class), searchPath(c));
			}
		}
		finally {
			TenantContextHolder.clear();
			for (Connection c : held) {
				c.close();
			}
		}
		assertEquals(POOL_SIZE, before.size(), "池里应有 " + POOL_SIZE + " 条不同的物理连接");
		return before;
	}

	/**
	 * 迁移后同时借出池里全部连接：每条物理连接的 search_path 与迁移前一致；再以 {@code tenant} 身份执行业务语句
	 * （记忆命中、不再 SET），必须落在 {@code expectedSchema}。所有偏差收集后一次性报出。
	 */
	private void assertPoolUntouchedAndLandsIn(Map<Connection, String> before, String tenant, String expectedSchema)
			throws SQLException {
		long landedBefore = landingRows(expectedSchema, tenant);
		List<String> problems = new ArrayList<>();
		List<Connection> held = new ArrayList<>();
		try {
			for (int i = 0; i < POOL_SIZE; i++) {
				Connection c = pool.getConnection();
				held.add(c);
				Connection physical = c.unwrap(Connection.class);
				String was = before.get(physical);
				if (was == null) {
					problems.add("物理连接 #" + i + " 不是迁移前那批（池重建了连接，判据失效）");
					continue;
				}
				String now = searchPath(c);
				if (!was.equals(now)) {
					problems.add("物理连接 #" + i + " search_path 迁移前 [" + was + "] 迁移后 [" + now + "]");
				}
				TenantContextHolder.setTenantId(tenant);
				String landed = business(c, tenant);
				if (!expectedSchema.equals(landed)) {
					problems.add("物理连接 #" + i + " 租户 " + tenant + " 的写入落进了 " + landed);
				}
			}
		}
		finally {
			TenantContextHolder.clear();
			for (Connection c : held) {
				c.close();
			}
		}
		assertTrue(problems.isEmpty(), String.join("\n", problems));
		assertEquals(landedBefore + POOL_SIZE, landingRows(expectedSchema, tenant),
				"每条连接的那次写入都应落在 " + expectedSchema);
	}

	/** 一条业务语句：先过真 SchemaInterceptor（记忆开），再不带 schema 前缀写 landing，返回实际落在哪个 schema。 */
	private String business(Connection c, String tenant) throws SQLException {
		interceptor.beforePrepare(null, c, null);
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO lofw_landing (tenant, owner) SELECT ?, owner FROM lofw_probe RETURNING owner")) {
			ps.setString(1, tenant);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	private static String searchPath(Connection c) throws SQLException {
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW search_path")) {
			rs.next();
			return rs.getString(1);
		}
	}

	/** 迁移用的物理连接用完必须物理关闭（服务端进程退出略有延迟，最多等 5 秒）。 */
	private void assertNoMigrationConnectionsLeft() throws Exception {
		long n = -1;
		for (int i = 0; i < 50; i++) {
			n = count("SELECT count(*) FROM pg_stat_activity WHERE application_name = '" + MIGRATE_APP + "'");
			if (n == 0) {
				return;
			}
			TimeUnit.MILLISECONDS.sleep(100);
		}
		assertEquals(0, n, "迁移物理连接没有关闭");
	}

	/**
	 * 在 schema 上建迁移历史，把 classpath 上的版本（除 {@code exceptVersion}）记为已应用，checksum 用 Flyway 自己算的。
	 * 走独立的非池连接，不碰业务池。
	 */
	private void markApplied(String schema, String exceptVersion) throws SQLException {
		Flyway fw = Flyway.configure().dataSource(admin).schemas(schema).locations(LOCATION).table(HISTORY)
			.placeholderReplacement(false).baselineVersion("1").load();
		fw.baseline();
		int rank = 2;
		try (Connection c = admin.getConnection();
				PreparedStatement ps = c.prepareStatement("INSERT INTO " + schema + "." + HISTORY
						+ " (installed_rank, version, description, type, script, checksum, installed_by, installed_on,"
						+ " execution_time, success) VALUES (?, ?, ?, 'SQL', ?, ?, 'lofw-test', now(), 0, true)")) {
			for (MigrationInfo m : fw.info().all()) {
				if (m.getState() != MigrationState.PENDING || m.getVersion() == null
						|| m.getVersion().getVersion().equals(exceptVersion)) {
					continue;
				}
				ps.setInt(1, rank++);
				ps.setString(2, m.getVersion().getVersion());
				ps.setString(3, m.getDescription());
				ps.setString(4, m.getScript());
				if (m.getChecksum() == null) {
					ps.setNull(5, java.sql.Types.INTEGER);
				}
				else {
					ps.setInt(5, m.getChecksum());
				}
				ps.addBatch();
			}
			ps.executeBatch();
		}
	}

	private long historyRows(String schema) throws SQLException {
		return count("SELECT count(*) FROM " + schema + "." + HISTORY);
	}

	private long landingRows(String schema, String tenant) throws SQLException {
		return count("SELECT count(*) FROM " + schema + ".lofw_landing WHERE tenant = '" + tenant + "'");
	}

	private long count(String sql) throws SQLException {
		try (Connection c = admin.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private void exec(String sql) throws SQLException {
		try (Connection c = admin.getConnection(); Statement st = c.createStatement()) {
			st.execute(sql);
		}
	}

	private static String withApp(String url, String app) {
		return url + (url.contains("?") ? "&" : "?") + "ApplicationName=" + app;
	}
}
