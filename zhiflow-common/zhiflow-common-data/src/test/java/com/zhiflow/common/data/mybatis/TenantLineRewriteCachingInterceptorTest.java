package com.zhiflow.common.data.mybatis;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.jsqlparser.enums.ExpressionAppendMode;
import com.zhiflow.common.data.tenant.BaseTenantHandler;
import com.zhiflow.common.data.tenant.TenantConfigProperties;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 租户条件改写结果缓存（恒开）：与官方 {@link TenantLineInnerInterceptor} 逐字节一致；同一 SQL 跨租户不串；
 * 配置刷新后结果随之变化（含「刷新与改写并发」）；并发交替租户无串号。
 * <p>
 * 期望值一律由官方拦截器（同一个处理器实例）在同一租户上下文里现算，不手写期望 SQL。
 */
@DisplayName("A1 TenantLine 改写结果缓存（租户隔离边界）")
class TenantLineRewriteCachingInterceptorTest {

	private static final String T1 = "1";

	private static final String T2 = "1000000000000000002";

	/** 依次覆盖：普通租户 ×2、系统租户、无租户。 */
	private static final String[] TENANTS = { T1, T2, "-1", null };

	private final TenantConfigProperties tenantProps = new TenantConfigProperties();

	private final BaseTenantHandler handler = new BaseTenantHandler();

	private final SqlRewriteConfigRefresh configRefresh = new SqlRewriteConfigRefresh();

	private final TenantLineRewriteCachingInterceptor caching;

	private final TenantLineInnerInterceptor official;

	TenantLineRewriteCachingInterceptorTest() {
		tenantProps.setTables(new ArrayList<>(List.of("order_info", "goods_spu", "coupon_user", "user_footprint")));
		ReflectionTestUtils.setField(handler, "tenantConfigProperties", tenantProps);
		caching = new TenantLineRewriteCachingInterceptor(handler, configRefresh);
		official = new TenantLineInnerInterceptor(handler);
	}

	@AfterEach
	void clearTenant() {
		TenantContextHolder.clear();
	}

	/** parserSingle 样本（select / insert / update / delete、子查询、join、CTE、union、PG 构造）。 */
	private static Map<String, String> singleSamples() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("select", "SELECT id, total FROM order_info WHERE del_flag = '0'");
		m.put("select-non-tenant-table", "SELECT id FROM sys_config WHERE k = 'x'");
		m.put("join", "SELECT o.id, g.name FROM order_info o JOIN goods_spu g ON g.id = o.spu_id WHERE o.id = ?");
		m.put("left-join", "SELECT o.id FROM order_info o LEFT JOIN coupon_user c ON c.order_id = o.id WHERE o.id = ?");
		m.put("subquery-in", "SELECT id FROM order_info WHERE user_id IN "
				+ "(SELECT user_id FROM user_footprint WHERE del_flag = '0')");
		m.put("exists", "SELECT id FROM goods_spu g WHERE EXISTS (SELECT 1 FROM order_info o WHERE o.spu_id = g.id)");
		m.put("cte", "WITH alive AS (SELECT user_id FROM user_footprint WHERE del_flag = '0') "
				+ "SELECT o.id FROM order_info o WHERE o.user_id IN (SELECT user_id FROM alive)");
		m.put("union", "SELECT id FROM order_info UNION ALL SELECT id FROM coupon_user");
		m.put("pg-cast", "SELECT id, (amount)::numeric(10,2) AS amt FROM order_info");
		m.put("pg-filter", "SELECT count(*) FILTER (WHERE status = '1') AS paid FROM coupon_user");
		// 审查补充：schema 前缀、带引号表名、OR 条件（追加时要加括号）、注释、子查询做 FROM
		m.put("schema-prefix", "SELECT o.id FROM zhiflow_mall_1.order_info o WHERE o.id = ?");
		m.put("quoted-table", "SELECT id FROM \"order_info\" WHERE id = ?");
		m.put("or-where", "SELECT id FROM order_info WHERE status = '1' OR status = '2'");
		m.put("comment", "/* order_info */ SELECT id FROM goods_spu WHERE id = ? -- coupon_user");
		m.put("from-subselect", "SELECT t.id FROM (SELECT id FROM order_info WHERE del_flag = '0') t JOIN coupon_user c ON c.order_id = t.id");
		return m;
	}

	/** parserMulti 样本（租户拦截器对 INSERT / UPDATE / DELETE 走 multi）。 */
	private static Map<String, String> multiSamples() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("insert", "INSERT INTO coupon_user (id, user_id) VALUES (?, ?)");
		m.put("insert-batch", "INSERT INTO order_info (id, user_id) VALUES (?, ?), (?, ?)");
		m.put("insert-has-tenant-col", "INSERT INTO order_info (id, tenant_id) VALUES (?, ?)");
		m.put("insert-select", "INSERT INTO order_info (id, user_id) SELECT id, user_id FROM coupon_user WHERE id = ?");
		m.put("insert-on-conflict", "INSERT INTO coupon_user (id, user_id) VALUES (?, ?) ON CONFLICT (id) DO NOTHING");
		m.put("update", "UPDATE goods_spu SET stock = stock - ? WHERE id = ? AND stock >= ?");
		m.put("update-subselect", "UPDATE order_info SET total = (SELECT sum(x) FROM coupon_user WHERE id = ?) WHERE id = ?");
		m.put("delete", "DELETE FROM coupon_user WHERE id = ?");
		m.put("delete-subquery", "DELETE FROM order_info WHERE id IN (SELECT order_id FROM coupon_user)");
		m.put("non-tenant-update", "UPDATE sys_config SET v = ? WHERE k = ?");
		m.put("insert-schema-prefix", "INSERT INTO zhiflow_mall_1.coupon_user (id, user_id) VALUES (?, ?)");
		m.put("update-alias-or", "UPDATE goods_spu g SET stock = 0 WHERE g.id = ? OR g.spu_code = ?");
		m.put("two-statements", "UPDATE order_info SET status = '2' WHERE id = 1; UPDATE coupon_user SET x = 1 WHERE id = 2");
		return m;
	}

	private static void setTenant(String tenantId) {
		if (tenantId == null) {
			TenantContextHolder.clear();
		}
		else {
			TenantContextHolder.setTenantId(tenantId);
		}
	}

	private String officialSingle(String tenantId, String sql) {
		setTenant(tenantId);
		return official.parserSingle(sql, null);
	}

	private String officialMulti(String tenantId, String sql) {
		setTenant(tenantId);
		return official.parserMulti(sql, null);
	}

	private String cachedSingle(String tenantId, String sql) {
		setTenant(tenantId);
		return caching.parserSingle(sql, null);
	}

	private String cachedMulti(String tenantId, String sql) {
		setTenant(tenantId);
		return caching.parserMulti(sql, null);
	}

	@Test
	@DisplayName("所有租户 × 所有样本与官方一致，第二轮全部走缓存")
	void matchesOfficial_andHits() {
		for (int round = 0; round < 2; round++) {
			for (String tenant : TENANTS) {
				int r = round;
				singleSamples().forEach((name, sql) ->
						assertEquals(officialSingle(tenant, sql), cachedSingle(tenant, sql), name + " tenant=" + tenant + " round=" + r));
				multiSamples().forEach((name, sql) ->
						assertEquals(officialMulti(tenant, sql), cachedMulti(tenant, sql), name + " tenant=" + tenant + " round=" + r));
			}
		}
		int perRound = TENANTS.length * (singleSamples().size() + multiSamples().size());
		assertEquals(perRound, caching.estimatedSize());
		assertEquals(perRound, caching.hitCount());
	}

	@Test
	@DisplayName("同一 SQL 两个租户交替执行：结果只带自己的租户号（select / insert / update / delete）")
	void sameSql_alternatingTenants_noCrossTenant() {
		String select = "SELECT o.id FROM order_info o JOIN goods_spu g ON g.id = o.spu_id WHERE o.id IN "
				+ "(SELECT order_id FROM coupon_user)";
		String insert = "INSERT INTO coupon_user (id, user_id) VALUES (?, ?)";
		String update = "UPDATE goods_spu SET stock = stock - ? WHERE id = ?";
		String delete = "DELETE FROM coupon_user WHERE id = ?";
		for (int i = 0; i < 4; i++) {
			for (String tenant : new String[] { T1, T2 }) {
				String other = tenant.equals(T1) ? T2 : T1;
				List<String> results = List.of(cachedSingle(tenant, select), cachedMulti(tenant, insert),
						cachedMulti(tenant, update), cachedMulti(tenant, delete));
				for (String sql : results) {
					assertTrue(sql.contains("'" + tenant + "'"), "缺本租户号: " + sql);
					assertFalse(sql.contains("'" + other + "'"), "串进了别的租户号: " + sql);
				}
			}
		}
		assertTrue(caching.hitCount() > 0, "判据自检：第二轮起应走缓存，否则上面的断言没测到缓存");
	}

	@Test
	@DisplayName("无租户：不注入，与官方一致，且与有租户的结果分开缓存")
	void nullTenant_separateEntry() {
		String sql = "SELECT id FROM order_info WHERE id = ?";
		String withTenant = cachedSingle(T1, sql);
		String noTenant = cachedSingle(null, sql);
		assertEquals(officialSingle(null, sql), noTenant);
		assertFalse(noTenant.contains("tenant_id"), noTenant);
		assertTrue(withTenant.contains("tenant_id = '1'"), withTenant);
		// 空白租户号与 null 等价（TenantContextHolder 已规范化），不能被当成另一个租户
		TenantContextHolder.setTenantId("  ");
		assertEquals(noTenant, caching.parserSingle(sql, null));
	}

	@Test
	@DisplayName("配置刷新：租户表增删、租户列改名后结果随之变化")
	void configRefresh_changesResult() {
		String sql = "SELECT id FROM sys_config WHERE k = ?";
		String before = cachedSingle(T1, sql);
		assertFalse(before.contains("tenant_id"), before);

		tenantProps.getTables().add("sys_config");
		configRefresh.onConfigChanged();
		String added = cachedSingle(T1, sql);
		assertTrue(added.contains("tenant_id = '1'"), added);
		assertEquals(officialSingle(T1, sql), added);

		tenantProps.setColumn("org_tenant");
		configRefresh.onConfigChanged();
		String renamed = cachedSingle(T1, sql);
		assertTrue(renamed.contains("org_tenant = '1'"), renamed);

		tenantProps.getTables().remove("sys_config");
		configRefresh.onConfigChanged();
		assertEquals(before, cachedSingle(T1, sql));
	}

	@Test
	@DisplayName("配置刷新：已缓存的条目全部作废，下一次重新计算")
	void configRefresh_invalidatesEntries() {
		String sql = "SELECT id FROM order_info";
		cachedSingle(T1, sql);
		assertEquals(1, caching.estimatedSize());
		configRefresh.onConfigChanged();
		assertEquals(0, caching.estimatedSize(), "刷新时已作废");
		long hits = caching.hitCount();
		assertEquals(officialSingle(T1, sql), cachedSingle(T1, sql));
		assertEquals(hits, caching.hitCount(), "作废后第一次不应命中");
	}

	@Test
	@DisplayName("刷新与改写并发：改写途中配置被刷新，旧代次算出的结果不会被新请求用到")
	void refreshDuringRewrite_staleEntryNotServed() {
		// 模拟「线程 A 正拿旧配置改写 → 配置刷新、作废 → A 才把旧结果放进缓存」：第一次读租户表时顺手完成一次刷新
		RacingTenantConfigProperties racing = new RacingTenantConfigProperties();
		racing.setTables(new ArrayList<>(List.of("order_info")));
		ReflectionTestUtils.setField(handler, "tenantConfigProperties", racing);
		racing.onFirstRead = () -> {
			racing.realTables = new ArrayList<>(List.of("order_info", "sys_config"));
			configRefresh.onConfigChanged();
		};

		String sql = "SELECT id FROM sys_config WHERE k = ?";
		String first = cachedSingle(T1, sql);
		assertFalse(first.contains("tenant_id"), "第一次用的是刷新前的表清单: " + first);
		String second = cachedSingle(T1, sql);
		assertTrue(second.contains("tenant_id = '1'"), "刷新后的请求拿到了旧结果: " + second);
	}

	/** 第一次 getTables() 时先返回旧清单、同时触发一次「刷新」，之后返回新清单。 */
	private static final class RacingTenantConfigProperties extends TenantConfigProperties {

		Runnable onFirstRead;

		List<String> realTables;

		private boolean fired;

		@Override
		public List<String> getTables() {
			if (!fired) {
				fired = true;
				List<String> old = super.getTables();
				onFirstRead.run();
				return old;
			}
			return realTables;
		}

	}

	@Test
	@DisplayName("同一文本先走 single 再走 multi：各自与官方一致（single/multi 在 key 里）")
	void singleThenMulti_sameText_separateEntries() {
		String sql = "UPDATE order_info SET status = '2' WHERE id = 1; UPDATE coupon_user SET x = 1 WHERE id = 2";
		String officialSingle = officialSingle(T1, sql);
		assertEquals(officialSingle, cachedSingle(T1, sql));
		String officialMulti = officialMulti(T1, sql);
		assertNotEquals(officialSingle, officialMulti, "判据自检：这条样本 single 与 multi 的结果必须不同");
		assertEquals(officialMulti, cachedMulti(T1, sql));
	}

	@Test
	@DisplayName("whereSegment 非 null：不走缓存")
	void nonNullObj_bypassesCache() {
		String sql = "SELECT id FROM order_info";
		setTenant(T1);
		assertEquals(official.parserSingle(sql, "seg"), caching.parserSingle(sql, "seg"));
		assertEquals(0, caching.estimatedSize());
	}

	@Test
	@DisplayName("处理器不是 BaseTenantHandler：不缓存")
	void foreignHandler_notCached() {
		TenantLineHandler foreign = new TenantLineHandler() {
			@Override
			public Expression getTenantId() {
				return new StringValue(String.valueOf(System.nanoTime()));
			}
		};
		TenantLineRewriteCachingInterceptor c = new TenantLineRewriteCachingInterceptor(foreign, configRefresh);
		setTenant(T1);
		String a = c.parserSingle("SELECT id FROM order_info", null);
		String b = c.parserSingle("SELECT id FROM order_info", null);
		assertNotEquals(a, b, "外来处理器的结果不可缓存");
		assertEquals(0, c.estimatedSize());
	}

	@Test
	@DisplayName("换追加模式：缓存作废，结果与官方同模式一致")
	void appendModeChange_invalidates() {
		String sql = "SELECT id FROM order_info WHERE a = 1 OR b = 2";
		String last = cachedSingle(T1, sql);
		caching.setExpressionAppendMode(ExpressionAppendMode.FIRST);
		official.setExpressionAppendMode(ExpressionAppendMode.FIRST);
		String first = cachedSingle(T1, sql);
		assertNotEquals(last, first);
		assertEquals(officialSingle(T1, sql), first);
	}

	@Test
	@DisplayName("解析失败：照常抛，不留缓存")
	void parseError_rethrownNotCached() {
		setTenant(T1);
		String bad = "SELEKT ;; FROM";
		assertThrows(RuntimeException.class, () -> caching.parserSingle(bad, null));
		assertThrows(RuntimeException.class, () -> caching.parserSingle(bad, null));
		assertEquals(0, caching.estimatedSize());
	}

	@Test
	@DisplayName("经 beforeQuery 真实入口：两个租户各自正确（证明官方入口调用的是子类）")
	void beforeQuery_entryPoint() {
		Configuration configuration = new Configuration();
		String sql = "SELECT id FROM order_info WHERE id = ?";
		MappedStatement ms = new MappedStatement.Builder(configuration, "test.OrderMapper.selectOne",
				new StaticSqlSource(configuration, sql), SqlCommandType.SELECT).build();
		for (int i = 0; i < 2; i++) {
			for (String tenant : new String[] { T1, T2 }) {
				setTenant(tenant);
				BoundSql boundSql = ms.getBoundSql(null);
				caching.beforeQuery(null, ms, null, RowBounds.DEFAULT, null, boundSql);
				assertTrue(boundSql.getSql().contains("tenant_id = '" + tenant + "'"), boundSql.getSql());
			}
		}
		assertTrue(caching.hitCount() >= 2);
	}

	@Test
	@DisplayName("并发：16 线程随机租户随机样本，结果与官方预算值逐条一致")
	void concurrent_noCrossTenant() throws Exception {
		Map<String, String> singles = singleSamples();
		Map<String, String> multis = multiSamples();
		// 期望值：每个 (租户, 单/多, SQL) 由官方拦截器预先算好
		Map<String, String> expected = new HashMap<>();
		for (String tenant : TENANTS) {
			singles.values().forEach(sql -> expected.put(key(tenant, false, sql), officialSingle(tenant, sql)));
			multis.values().forEach(sql -> expected.put(key(tenant, true, sql), officialMulti(tenant, sql)));
		}
		TenantContextHolder.clear();
		List<String> singleList = new ArrayList<>(singles.values());
		List<String> multiList = new ArrayList<>(multis.values());

		int threads = 16;
		int iterations = 3000;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		ConcurrentLinkedQueue<String> mismatches = new ConcurrentLinkedQueue<>();
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				futures.add(pool.submit(() -> {
					start.await();
					ThreadLocalRandom rnd = ThreadLocalRandom.current();
					for (int i = 0; i < iterations; i++) {
						String tenant = TENANTS[rnd.nextInt(TENANTS.length)];
						boolean multi = rnd.nextBoolean();
						List<String> pickFrom = multi ? multiList : singleList;
						String sql = pickFrom.get(rnd.nextInt(pickFrom.size()));
						setTenant(tenant);
						try {
							String got = multi ? caching.parserMulti(sql, null) : caching.parserSingle(sql, null);
							String want = expected.get(key(tenant, multi, sql));
							if (!Objects.equals(want, got)) {
								mismatches.add("tenant=" + tenant + " sql=" + sql + "\n  want=" + want + "\n  got =" + got);
							}
						}
						finally {
							TenantContextHolder.clear();
						}
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> f : futures) {
				f.get(60, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertTrue(mismatches.isEmpty(), mismatches.size() + " 条不一致，前几条:\n"
				+ String.join("\n", mismatches.stream().limit(5).toList()));
		assertTrue(caching.hitCount() > threads * iterations / 2, "判据自检：大部分调用应命中缓存");
	}

	private static String key(String tenant, boolean multi, String sql) {
		return tenant + "|" + multi + "|" + sql;
	}

}
