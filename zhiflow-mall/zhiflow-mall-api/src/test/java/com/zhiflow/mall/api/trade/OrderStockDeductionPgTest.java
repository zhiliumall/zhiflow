package com.zhiflow.mall.api.trade;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.zhiflow.common.data.mybatis.SchemaInterceptor;
import com.zhiflow.common.data.shardingdatabase.config.ShardingDataBaseProperties;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.api.mapper.GoodsSkuMapper;
import com.zhiflow.mall.common.entity.OrderItem;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 10-18 B1：真 PG 上验证下单扣库存的等锁上限与加锁顺序——生产 {@link GoodsSkuMapper}、生产 {@link OrderStockDeduction}，
 * 经生产同款拦截器链（{@link SchemaInterceptor} 每条语句前 SET search_path + 租户行拦截器）与 MyBatis-Spring 异常翻译。
 *
 * <p>默认整类跳过。显式指定本机库才运行（只接受回环地址），例如：
 * {@code -Dhotsku.pg.url=jdbc:postgresql://127.0.0.1:5432/ot_hot_sku}，用户默认 postgres，
 * 密码从环境变量 {@code HOTSKU_PG_PASSWORD}（或 {@code -Dhotsku.pg.password}）取，不写进任何文件。
 * 每次建唯一 schema（{@code hs_<uuid>_9}），结束后删除。
 *
 * <p>连接级默认 {@code lock_timeout=7s}（连接参数），用来证明扣完恢复的是「连接默认值」而不是 PG 出厂的 0，
 * 也给「开关关着照旧等锁」的用例一个有限上界。
 */
@EnabledIf("pgConfigured")
class OrderStockDeductionPgTest {

	private static final String DB_PREFIX = "hs_" + UUID.randomUUID().toString().replace("-", "");

	private static final String TENANT = "9";

	private static final String SCHEMA = DB_PREFIX + "_" + TENANT;

	private static final String CONNECTION_LOCK_TIMEOUT = "7s";

	private static PGSimpleDataSource dataSource;

	private static JdbcTemplate jdbc;

	private static TransactionTemplate tx;

	private static GoodsSkuMapper realMapper;

	private static boolean schemaCreated;

	private final ExecutorService pool = Executors.newFixedThreadPool(4);

	static boolean pgConfigured() {
		String url = System.getProperty("hotsku.pg.url");
		return url != null && !url.isBlank();
	}

	private static String password() {
		String p = System.getProperty("hotsku.pg.password");
		return p != null ? p : System.getenv().getOrDefault("HOTSKU_PG_PASSWORD", "");
	}

	@BeforeAll
	static void createFixture() {
		String url = System.getProperty("hotsku.pg.url");
		assertTrue(url.startsWith("jdbc:postgresql://"), "仅支持显式指定的 PG JDBC 地址");
		URI endpoint = URI.create(url.substring("jdbc:".length()));
		assertTrue(List.of("127.0.0.1", "localhost", "[::1]", "::1").contains(endpoint.getHost()), "只允许本机回环地址");

		dataSource = new PGSimpleDataSource();
		dataSource.setURL(url);
		dataSource.setUser(System.getProperty("hotsku.pg.user", "postgres"));
		dataSource.setPassword(password());
		dataSource.setApplicationName(DB_PREFIX);
		dataSource.setOptions("-c statement_timeout=30000 -c lock_timeout=" + CONNECTION_LOCK_TIMEOUT);
		// 与默认 mall 数据源 URL 一致：第一次执行就走服务端预编译（SET LOCAL / set_config 也会被预编译）
		dataSource.setPrepareThreshold(1);
		jdbc = new JdbcTemplate(dataSource);
		try {
			jdbc.execute("CREATE SCHEMA " + SCHEMA);
			schemaCreated = true;
			jdbc.execute("CREATE TABLE " + SCHEMA + ".goods_sku (id varchar(32), tenant_id varchar(32),"
					+ " stock integer NOT NULL, version integer, PRIMARY KEY (id, tenant_id))");
			// 扣库存之前同一事务里已写过的东西（订单 / 券核销 / 积分），用来证明抢锁失败整单回滚无残留
			jdbc.execute("CREATE TABLE " + SCHEMA + ".order_info (id varchar(32) PRIMARY KEY, tenant_id varchar(32))");
			jdbc.execute("CREATE TABLE " + SCHEMA + ".coupon_user (id varchar(32) PRIMARY KEY, status varchar(2))");
			jdbc.execute("CREATE TABLE " + SCHEMA + ".user_points (id varchar(32) PRIMARY KEY, points_current integer)");

			tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
			tx.setTimeout(20);

			ShardingDataBaseProperties props = new ShardingDataBaseProperties();
			props.setDbname(DB_PREFIX);
			props.setDefaultTenantId(TENANT);
			MybatisPlusInterceptor interceptors = new MybatisPlusInterceptor();
			interceptors.addInnerInterceptor(new SchemaInterceptor(props));
			TenantLineInnerInterceptor tenantLine = new TenantLineInnerInterceptor();
			tenantLine.setTenantLineHandler(new TenantLineHandler() {
				@Override
				public Expression getTenantId() {
					return new StringValue(TenantContextHolder.getTenantId());
				}

				@Override
				public boolean ignoreTable(String tableName) {
					return !"goods_sku".equals(tableName);
				}
			});
			interceptors.addInnerInterceptor(tenantLine);

			MybatisConfiguration cfg = new MybatisConfiguration();
			cfg.setMapUnderscoreToCamelCase(true);
			cfg.setLogImpl(NoLoggingImpl.class);
			cfg.setEnvironment(new Environment("hot-sku-pg", new SpringManagedTransactionFactory(), dataSource));
			cfg.addInterceptor(interceptors);
			cfg.addMapper(GoodsSkuMapper.class);
			realMapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg))
					.getMapper(GoodsSkuMapper.class);
		}
		catch (RuntimeException | AssertionError e) {
			dropFixture();
			throw e;
		}
	}

	@AfterAll
	static void dropFixture() {
		if (schemaCreated) {
			jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
			schemaCreated = false;
		}
	}

	@BeforeEach
	void reset() {
		jdbc.execute("TRUNCATE " + SCHEMA + ".goods_sku, " + SCHEMA + ".order_info, " + SCHEMA + ".coupon_user, "
				+ SCHEMA + ".user_points");
		for (String sku : List.of("A", "B", "C")) {
			jdbc.update("INSERT INTO " + SCHEMA + ".goods_sku (id, tenant_id, stock, version) VALUES (?, ?, 10, 0)", sku,
					TENANT);
		}
		// 别的租户同 id 的行：租户行拦截器生效的对照（扣库存只能扣到本租户那行）
		jdbc.update("INSERT INTO " + SCHEMA + ".goods_sku (id, tenant_id, stock, version) VALUES ('A', 'x', 10, 0)");
		jdbc.update("INSERT INTO " + SCHEMA + ".coupon_user (id, status) VALUES ('c1', '0')");
		jdbc.update("INSERT INTO " + SCHEMA + ".user_points (id, points_current) VALUES ('u1', 100)");
		TenantContextHolder.setTenantId(TENANT);
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		OrderStockDeductionTest.removeBusyMeters();
		TenantContextHolder.clear();
		pool.shutdownNow();
		pool.awaitTermination(10, TimeUnit.SECONDS);
	}

	@Test
	@DisplayName("开关开：持锁方 > 2 秒不提交 ⇒ 下单方约 2 秒失败、得到 70008，整单回滚（订单 / 券 / 积分 / 库存都没变）")
	void lockHeldPastLimitFailsInAboutTwoSecondsAndRollsBackEverything() throws Exception {
		OrderStockDeduction deduction = deduction(true, null);
		try (Connection holder = holdRow("A")) {
			long[] elapsed = new long[1];
			StockBusyException busy = assertThrows(StockBusyException.class, () -> inTx(() -> {
				jdbc.update("INSERT INTO " + SCHEMA + ".order_info (id, tenant_id) VALUES ('o1', ?)", TENANT);
				jdbc.update("UPDATE " + SCHEMA + ".coupon_user SET status = '1' WHERE id = 'c1' AND status = '0'");
				jdbc.update("UPDATE " + SCHEMA + ".user_points SET points_current = points_current - 10 WHERE id = 'u1'");
				long start = System.nanoTime();
				try {
					deduction.decrease(realMapper, List.of(item("A", 1)));
				}
				finally {
					elapsed[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
				}
			}));
			holder.rollback();

			assertTrue(elapsed[0] >= 1900 && elapsed[0] < 3500, "应在约 2 秒处失败，实际 " + elapsed[0] + "ms");
			assertEquals(70008, StockBusyException.CODE.getCode());
			assertEquals("商品太火爆，请稍后再试", busy.getMessage());
			CannotAcquireLockException cause = assertInstanceOf(CannotAcquireLockException.class, busy.getCause(),
					"MyBatis-Spring 把 55P03 翻成 CannotAcquireLockException");
			assertEquals("55P03", assertInstanceOf(PSQLException.class, cause.getMostSpecificCause()).getSQLState());
		}
		assertEquals(0, count("SELECT count(*) FROM " + SCHEMA + ".order_info"), "订单回滚");
		assertEquals("0", jdbc.queryForObject("SELECT status FROM " + SCHEMA + ".coupon_user WHERE id = 'c1'", String.class),
				"券回滚为未使用");
		assertEquals(100, count("SELECT points_current FROM " + SCHEMA + ".user_points WHERE id = 'u1'"), "积分回滚");
		assertEquals(10, stock("A"), "库存没扣");
	}

	@Test
	@DisplayName("开关开、不抢锁：按 skuId 升序扣、数量精确、只扣本租户那行；扣完 lock_timeout 恢复成连接默认值")
	void noContentionDecrementsAndRestoresConnectionDefault() {
		List<String> calls = Collections.synchronizedList(new ArrayList<>());
		OrderStockDeduction deduction = deduction(true, null);
		String[] seen = new String[3];
		inTx(() -> {
			seen[0] = currentLockTimeout();
			deduction.decrease(recording(calls, null), List.of(item("B", 2), item("A", 1)));
			seen[1] = currentLockTimeout();
		});
		seen[2] = currentLockTimeout();

		assertEquals(CONNECTION_LOCK_TIMEOUT, seen[0]);
		assertEquals(CONNECTION_LOCK_TIMEOUT, seen[1], "同事务后续语句的 lock_timeout 已恢复");
		assertEquals(CONNECTION_LOCK_TIMEOUT, seen[2]);
		assertEquals(List.of("setLocalLockTimeout:2000ms", "decreaseStock:A:1", "decreaseStock:B:2", "resetLocalLockTimeout"),
				calls);
		assertEquals(9, stock("A"));
		assertEquals(8, stock("B"));
		assertEquals(10, count("SELECT stock FROM " + SCHEMA + ".goods_sku WHERE id = 'A' AND tenant_id = 'x'"), "别的租户的行不受影响");
	}

	@Test
	@DisplayName("开关开：扣完之后同一事务里再等别的锁，不受 2 秒限制（等了 3 秒照样拿到）")
	void lockTimeoutDoesNotLeakToLaterStatementsInSameTransaction() throws Exception {
		OrderStockDeduction deduction = deduction(true, null);
		long[] waited = new long[1];
		try (Connection holder = holdRow("C")) {
			pool.submit(() -> {
				TimeUnit.MILLISECONDS.sleep(3000);
				holder.rollback();
				return null;
			});
			inTx(() -> {
				deduction.decrease(realMapper, List.of(item("A", 1)));
				long start = System.nanoTime();
				jdbc.update("UPDATE " + SCHEMA + ".goods_sku SET stock = stock - 1 WHERE id = 'C' AND tenant_id = '" + TENANT + "'");
				waited[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			});
		}
		assertTrue(waited[0] >= 2500, "后续语句应等到持锁方放手（约 3 秒），实际 " + waited[0] + "ms");
		assertEquals(9, stock("A"));
		assertEquals(9, stock("C"));
	}

	@Test
	@DisplayName("开关开：两单对 A、B 反序提交、交错执行，按 skuId 排序后不再死锁，两单都成功")
	void reverseOrderConcurrentOrdersDoNotDeadlock() throws Exception {
		OrderStockDeduction deduction = deduction(true, null);
		for (int round = 0; round < 3; round++) {
			// 每单扣完第一项后等对方也扣完第一项（最多 800ms）：不排序时两单各攥一行再去拿对方那行，必然成环
			CountDownLatch bothHoldFirst = new CountDownLatch(2);
			Future<?> ab = pool.submit(() -> order(deduction, bothHoldFirst, List.of(item("A", 1), item("B", 1))));
			Future<?> ba = pool.submit(() -> order(deduction, bothHoldFirst, List.of(item("B", 1), item("A", 1))));
			ab.get(15, TimeUnit.SECONDS);
			ba.get(15, TimeUnit.SECONDS);
		}
		assertEquals(4, stock("A"), "3 轮 × 2 单各扣 1");
		assertEquals(4, stock("B"));
	}

	@Test
	@DisplayName("开关开：别的路径反序拿同两行（先 B 后 A）真撞死锁 ⇒ 被裁掉的下单方得到 70008（40P01 → DeadlockLoser），整单回滚")
	void deadlockWithForeignPathBecomesStockBusy() throws Exception {
		OrderStockDeduction deduction = deduction(true, null);
		try (Connection foreign = holdRow("B")) {
			// 下单方先拿 A、再等 B；300ms 后外部事务去拿 A 成环。下单方先开始等，deadlock_timeout（1s）先到它，由它检测并被裁掉
			Future<?> foreignTakesA = pool.submit(() -> {
				TimeUnit.MILLISECONDS.sleep(300);
				try (Statement st = foreign.createStatement()) {
					st.executeUpdate("UPDATE " + SCHEMA + ".goods_sku SET stock = stock WHERE id = 'A' AND tenant_id = '"
							+ TENANT + "'");
				}
				return null;
			});
			StockBusyException busy = assertThrows(StockBusyException.class,
					() -> inTx(() -> deduction.decrease(realMapper, List.of(item("B", 1), item("A", 1)))));
			org.springframework.dao.DeadlockLoserDataAccessException deadlock = assertInstanceOf(
					org.springframework.dao.DeadlockLoserDataAccessException.class, busy.getCause());
			assertEquals("40P01", assertInstanceOf(PSQLException.class, deadlock.getMostSpecificCause()).getSQLState());
			foreignTakesA.get(10, TimeUnit.SECONDS);
			foreign.rollback();
		}
		assertEquals(10, stock("A"), "整单回滚");
		assertEquals(10, stock("B"));
	}

	@Test
	@DisplayName("开关关：仍按 skuId 升序扣（10-26 起顺序与开关解耦）、不发任何额外语句，等锁不受 2 秒限制（等了 2.6 秒照样扣成）")
	void switchOffKeepsLegacyBehaviour() throws Exception {
		List<String> calls = Collections.synchronizedList(new ArrayList<>());
		OrderStockDeduction deduction = deduction(false, null);
		long[] waited = new long[1];
		try (Connection holder = holdRow("A")) {
			pool.submit(() -> {
				TimeUnit.MILLISECONDS.sleep(2600);
				holder.rollback();
				return null;
			});
			inTx(() -> {
				long start = System.nanoTime();
				deduction.decrease(recording(calls, null), List.of(item("B", 1), item("A", 1)));
				waited[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			});
		}
		assertEquals(List.of("decreaseStock:A:1", "decreaseStock:B:1"), calls);
		assertTrue(waited[0] >= 2400, "开关关着照旧等锁，实际 " + waited[0] + "ms");
		assertEquals(9, stock("A"));
		assertEquals(9, stock("B"));
	}

	@Test
	@DisplayName("开关开、库存不足：仍是原来的「请重新提交」，不是「商品太火爆」")
	void insufficientStockKeepsOriginalMessage() {
		OrderStockDeduction deduction = deduction(true, null);
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> inTx(() -> deduction.decrease(realMapper, List.of(item("A", 1), item("B", 11)))));
		assertEquals("请重新提交", ex.getMessage());
		assertFalse(ex instanceof StockBusyException);
		assertEquals(10, stock("A"), "整单回滚，A 也没扣");
		assertEquals(10, stock("B"));
	}

	@Test
	@DisplayName("开关开、自定义 300ms：约 300ms 失败")
	void customTimeoutIsHonoured() throws Exception {
		OrderStockDeduction deduction = deduction(true, "300");
		long[] elapsed = new long[1];
		try (Connection holder = holdRow("A")) {
			assertThrows(StockBusyException.class, () -> inTx(() -> {
				long start = System.nanoTime();
				try {
					deduction.decrease(realMapper, List.of(item("A", 1)));
				}
				finally {
					elapsed[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
				}
			}));
			holder.rollback();
		}
		assertTrue(elapsed[0] >= 250 && elapsed[0] < 1500, "实际 " + elapsed[0] + "ms");
	}

	@Test
	@DisplayName("复核补：多店铺同一事务扣两次，第二家店的扣减照样受 2 秒限制（中间已复位成连接默认值）")
	void secondShopInSameTransactionIsStillLimited() throws Exception {
		OrderStockDeduction deduction = deduction(true, null);
		long[] elapsed = new long[1];
		String[] between = new String[1];
		try (Connection holder = holdRow("C")) {
			assertThrows(StockBusyException.class, () -> inTx(() -> {
				deduction.decrease(realMapper, List.of(item("A", 1)));
				between[0] = currentLockTimeout();
				long start = System.nanoTime();
				try {
					deduction.decrease(realMapper, List.of(item("C", 1)));
				}
				finally {
					elapsed[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
				}
			}));
			holder.rollback();
		}
		assertEquals(CONNECTION_LOCK_TIMEOUT, between[0]);
		assertTrue(elapsed[0] >= 1900 && elapsed[0] < 3500, "第二家店应在约 2 秒处失败，实际 " + elapsed[0] + "ms");
		assertEquals(10, stock("A"), "整单回滚，第一家店扣的也撤销");
		assertEquals(10, stock("C"));
	}

	@Test
	@DisplayName("复核补：setLocalLockTimeout 是带副作用的 SELECT，同一事务同参数再调必须真发语句（不吃 MyBatis 一级缓存）")
	void repeatedSetInSameTransactionIsNotServedFromLocalCache() {
		String[] seen = new String[1];
		inTx(() -> {
			realMapper.setLocalLockTimeout("2000ms");
			// 绕过 MyBatis（不清一级缓存）改掉本事务的值，再用同参数调一次 mapper
			jdbc.execute("SET LOCAL lock_timeout = '5s'");
			realMapper.setLocalLockTimeout("2000ms");
			seen[0] = currentLockTimeout();
		});
		assertEquals("2s", seen[0]);
	}

	@Test
	@DisplayName("2026-10-23 RETURNING：返回扣后剩余库存；version + 1；只扣本租户那行；库存不足返回 null 且不改行")
	void decreaseStockReturnsRemainingWithSameSemantics() {
		Integer[] remaining = new Integer[3];
		inTx(() -> {
			remaining[0] = realMapper.decreaseStock("A", 3);
			// 同一事务同参数再扣一次：必须真发语句（flushCache），不能吃一级缓存
			remaining[1] = realMapper.decreaseStock("A", 3);
			remaining[2] = realMapper.decreaseStock("B", 11);
		});

		assertEquals(7, remaining[0]);
		assertEquals(4, remaining[1]);
		assertEquals(null, remaining[2], "stock >= qty 守卫：库存不足返回 null");
		assertEquals(4, stock("A"));
		assertEquals(2, count("SELECT version FROM " + SCHEMA + ".goods_sku WHERE id = 'A' AND tenant_id = '" + TENANT + "'"));
		assertEquals(10, stock("B"));
		assertEquals(0, count("SELECT version FROM " + SCHEMA + ".goods_sku WHERE id = 'B' AND tenant_id = '" + TENANT + "'"));
		assertEquals(10, count("SELECT stock FROM " + SCHEMA + ".goods_sku WHERE id = 'A' AND tenant_id = 'x'"), "别的租户的行不受影响");
		assertEquals(0, count("SELECT version FROM " + SCHEMA + ".goods_sku WHERE id = 'A' AND tenant_id = 'x'"));
	}

	@Test
	@DisplayName("2026-10-23 RETURNING：扣到正好 0 返回 0（售罄），再扣返回 null；回滚后库存恢复")
	void soldOutReturnsZeroThenNull() {
		Integer[] remaining = new Integer[2];
		inTx(() -> {
			remaining[0] = realMapper.decreaseStock("C", 10);
			remaining[1] = realMapper.decreaseStock("C", 1);
		});
		assertEquals(0, remaining[0]);
		assertEquals(null, remaining[1]);
		assertEquals(0, stock("C"));

		assertThrows(RuntimeException.class, () -> inTx(() -> {
			assertEquals(9, realMapper.decreaseStock("B", 1));
			throw new IllegalStateException("rollback");
		}));
		assertEquals(10, stock("B"));
	}

	@Test
	@DisplayName("10-26：开关关着，两单对 A、B 反序提交、交错执行，同样按 skuId 排序、不死锁，两单都成功")
	void switchOffReverseOrderConcurrentOrdersDoNotDeadlock() throws Exception {
		OrderStockDeduction deduction = deduction(false, null);
		for (int round = 0; round < 3; round++) {
			CountDownLatch bothHoldFirst = new CountDownLatch(2);
			Future<?> ab = pool.submit(() -> order(deduction, bothHoldFirst, List.of(item("A", 1), item("B", 1))));
			Future<?> ba = pool.submit(() -> order(deduction, bothHoldFirst, List.of(item("B", 1), item("A", 1))));
			ab.get(15, TimeUnit.SECONDS);
			ba.get(15, TimeUnit.SECONDS);
		}
		assertEquals(4, stock("A"), "3 轮 × 2 单各扣 1");
		assertEquals(4, stock("B"));
	}

	// ------------------------------------------------------------------ helpers

	private void order(OrderStockDeduction deduction, CountDownLatch bothHoldFirst, List<OrderItem> items) {
		TenantContextHolder.setTenantId(TENANT);
		try {
			inTx(() -> deduction.decrease(recording(new ArrayList<>(), first -> {
				bothHoldFirst.countDown();
				try {
					bothHoldFirst.await(800, TimeUnit.MILLISECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}), items));
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	private static OrderStockDeduction deduction(boolean enabled, String ms) {
		MockEnvironment env = new MockEnvironment().withProperty(OrderStockDeduction.ENABLED_KEY, String.valueOf(enabled));
		if (ms != null) {
			env.setProperty(OrderStockDeduction.TIMEOUT_MS_KEY, ms);
		}
		return new OrderStockDeduction(env);
	}

	private static void inTx(Runnable body) {
		tx.executeWithoutResult(s -> body.run());
	}

	/** 另一条连接上锁住一行不提交（持锁方）。调用方负责 rollback / close。 */
	private static Connection holdRow(String skuId) throws Exception {
		Connection c = dataSource.getConnection();
		c.setAutoCommit(false);
		try (Statement st = c.createStatement()) {
			st.executeUpdate("UPDATE " + SCHEMA + ".goods_sku SET stock = stock WHERE id = '" + skuId + "' AND tenant_id = '" + TENANT + "'");
		}
		return c;
	}

	/** 包一层真实 mapper：记录调用顺序；afterFirstDecrease 在第一条扣库存成功后回调一次（制造交错）。 */
	private static GoodsSkuMapper recording(List<String> calls, Consumer<String> afterFirstDecrease) {
		boolean[] fired = new boolean[1];
		return (GoodsSkuMapper) Proxy.newProxyInstance(GoodsSkuMapper.class.getClassLoader(),
				new Class<?>[] { GoodsSkuMapper.class }, (proxy, method, args) -> {
					Object result;
					try {
						result = method.invoke(realMapper, args);
					}
					catch (InvocationTargetException e) {
						throw e.getCause();
					}
					StringBuilder call = new StringBuilder(method.getName());
					if (args != null) {
						for (Object a : args) {
							call.append(':').append(a);
						}
					}
					calls.add(call.toString());
					if ("decreaseStock".equals(method.getName()) && !fired[0] && afterFirstDecrease != null) {
						fired[0] = true;
						afterFirstDecrease.accept(String.valueOf(args[0]));
					}
					return result;
				});
	}

	private static OrderItem item(String skuId, int quantity) {
		OrderItem item = new OrderItem();
		item.setSkuId(skuId);
		item.setQuantity(quantity);
		return item;
	}

	private static String currentLockTimeout() {
		return jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
	}

	private static int stock(String skuId) {
		return count("SELECT stock FROM " + SCHEMA + ".goods_sku WHERE id = '" + skuId + "' AND tenant_id = '" + TENANT + "'");
	}

	private static int count(String sql) {
		Integer n = jdbc.queryForObject(sql, Integer.class);
		return n == null ? 0 : n;
	}

}
