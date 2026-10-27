package com.zhiflow.common.data.mybatis;

import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.zhiflow.common.data.shardingtable.config.ShardingTableProperties;
import com.zhiflow.common.data.shardingtable.handler.ShardingTableHandler;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 动态表名快速跳过（恒开）：与官方 {@link DynamicTableNameInnerInterceptor} 逐字节一致；
 * 只在结果必然是原 SQL 时跳过；分表名单刷新后随之变化。期望值一律由官方拦截器（同一处理器）现算。
 */
@DisplayName("A2 DynamicTableName 快速跳过")
class FastSkipDynamicTableNameInnerInterceptorTest {

	private static final String[] TENANTS = { "1", "1000000000000000002", "-1", null };

	private final ShardingTableProperties props = new ShardingTableProperties();

	private final ShardingTableHandler handler = new ShardingTableHandler();

	private final SqlRewriteConfigRefresh configRefresh = new SqlRewriteConfigRefresh();

	private final FastSkipDynamicTableNameInnerInterceptor fast;

	private final DynamicTableNameInnerInterceptor official = new DynamicTableNameInnerInterceptor(handler);

	FastSkipDynamicTableNameInnerInterceptorTest() {
		props.setTables(new ArrayList<>(List.of("sys_log", "sys_log_login")));
		ReflectionTestUtils.setField(handler, "shardingTableProperties", props);
		fast = new FastSkipDynamicTableNameInnerInterceptor(handler, props::getTables, configRefresh);
	}

	@AfterEach
	void clearTenant() {
		TenantContextHolder.clear();
	}

	private static Map<String, String> samples() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("unrelated-select", "SELECT o.id FROM order_info o JOIN goods_spu g ON g.id = o.spu_id WHERE o.id = ?");
		m.put("unrelated-insert", "INSERT INTO order_info (id, user_id) VALUES (?, ?)");
		m.put("unrelated-update", "UPDATE goods_spu SET stock = stock - ? WHERE id = ? AND stock >= ?");
		m.put("shard-select", "SELECT * FROM sys_log WHERE id = ?");
		m.put("shard-insert", "INSERT INTO sys_log (id, title) VALUES (?, ?)");
		m.put("shard-update", "UPDATE sys_log SET title = ? WHERE id = ?");
		m.put("shard-delete", "DELETE FROM sys_log WHERE id = ?");
		m.put("shard-upper", "select * from SYS_LOG where id = ?");
		m.put("shard-mixed", "SELECT * FROM Sys_Log WHERE id = ?");
		m.put("shard-schema-prefix", "SELECT * FROM zhiflow_mall.sys_log WHERE id = ?");
		m.put("shard-longer-name", "INSERT INTO sys_log_login (id) VALUES (?)");
		m.put("shard-join", "SELECT l.id FROM sys_log l JOIN order_info o ON o.id = l.order_id");
		m.put("name-in-literal", "SELECT id FROM order_info WHERE remark = 'sys_log'");
		m.put("name-in-column", "SELECT sys_log_count FROM order_info");
		m.put("name-as-prefix-of-other-table", "SELECT id FROM sys_logger WHERE id = ?");
		// 审查补充：带引号表名、注释里的表名、带引号 schema 前缀、逗号隐式连接
		m.put("quoted-table", "SELECT * FROM \"sys_log\" WHERE id = ?");
		m.put("name-in-block-comment", "/* sys_log */ SELECT id FROM order_info WHERE id = ?");
		m.put("name-in-line-comment", "SELECT id FROM order_info WHERE id = ? -- sys_log");
		m.put("quoted-schema-prefix", "SELECT * FROM \"zhiflow_mall\".sys_log WHERE id = ?");
		m.put("comma-join", "SELECT l.id FROM order_info o, sys_log l WHERE o.id = l.order_id");
		return m;
	}

	private void assertMatchesOfficial(String label) {
		for (String tenant : TENANTS) {
			setTenant(tenant);
			samples().forEach((name, sql) -> assertEquals(official.changeTable(sql), fast.changeTable(sql),
					label + " / " + name + " / tenant=" + tenant));
		}
	}

	private static void setTenant(String tenantId) {
		if (tenantId == null) {
			TenantContextHolder.clear();
		}
		else {
			TenantContextHolder.setTenantId(tenantId);
		}
	}

	@Test
	@DisplayName("两份名单下所有租户 × 所有样本都与官方一致，且确实跳过了")
	void matchesOfficial() {
		assertMatchesOfficial("tables=[sys_log, sys_log_login]");
		assertTrue(skippedCount() > 0, "判据自检：应有被跳过的 SQL，否则上面只测了父类");

		// 只留 sys_log：sys_log_login 里含 sys_log 子串，只会「不跳过」，交给父类，父类不改名
		props.setTables(new ArrayList<>(List.of("sys_log")));
		configRefresh.onConfigChanged();
		assertMatchesOfficial("tables=[sys_log]");

		// 分表名单为空：任何 SQL 都跳过，结果仍与官方一致
		props.setTables(new ArrayList<>());
		configRefresh.onConfigChanged();
		assertMatchesOfficial("tables=[]");
	}

	@Test
	@DisplayName("判定：租户为空 / 系统租户 / 不含分表名才跳过，大小写与 schema 前缀不跳过")
	void canSkip_cases() {
		TenantContextHolder.setTenantId("1");
		assertTrue(fast.canSkip("SELECT id FROM order_info"));
		assertFalse(fast.canSkip("SELECT * FROM sys_log"));
		assertFalse(fast.canSkip("SELECT * FROM SYS_LOG"));
		assertFalse(fast.canSkip("SELECT * FROM zhiflow_mall.sys_log"));
		assertFalse(fast.canSkip("SELECT id FROM sys_logger"), "子串只会不跳过");
		TenantContextHolder.setTenantId("-1");
		assertTrue(fast.canSkip("SELECT * FROM sys_log"));
		TenantContextHolder.clear();
		assertTrue(fast.canSkip("SELECT * FROM sys_log"));
	}

	@Test
	@DisplayName("分表名单刷新：从空到有，下一条 SQL 就按新名单改名")
	void tablesRefresh_takesEffect() {
		props.setTables(new ArrayList<>());
		configRefresh.onConfigChanged();
		TenantContextHolder.setTenantId("1");
		String sql = "SELECT * FROM sys_log WHERE id = ?";
		assertEquals(sql, fast.changeTable(sql));

		props.setTables(new ArrayList<>(List.of("sys_log")));
		configRefresh.onConfigChanged();
		assertEquals("SELECT * FROM sys_log_1 WHERE id = ?", fast.changeTable(sql));
		assertEquals(official.changeTable(sql), fast.changeTable(sql));
	}

	@Test
	@DisplayName("跳过时 hook 照样执行")
	void hook_runsOnSkip() {
		AtomicInteger calls = new AtomicInteger();
		fast.setHook(calls::incrementAndGet);
		TenantContextHolder.setTenantId("1");
		fast.changeTable("SELECT id FROM order_info");
		assertEquals(1L, skippedCount());
		assertEquals(1, calls.get());
	}

	@Test
	@DisplayName("处理器不是 ShardingTableHandler：不跳过，计入 parsed")
	void foreignHandler_neverSkips() {
		FastSkipDynamicTableNameInnerInterceptor foreign = new FastSkipDynamicTableNameInnerInterceptor(
				(sql, table) -> table + "_x", props::getTables, configRefresh);
		TenantContextHolder.setTenantId("1");
		assertEquals("SELECT id FROM order_info_x", foreign.changeTable("SELECT id FROM order_info"));
		assertEquals(0L, counter(foreign, "skipped"));
		assertEquals(1L, counter(foreign, "parsed"));
	}

	@Test
	@DisplayName("containsIgnoreCase：精确 / 大小写 / 不含 / 比原串长 / 在末尾")
	void containsIgnoreCase_cases() {
		assertTrue(FastSkipDynamicTableNameInnerInterceptor.containsIgnoreCase("from sys_log", "sys_log"));
		assertTrue(FastSkipDynamicTableNameInnerInterceptor.containsIgnoreCase("FROM SYS_LOG", "sys_log"));
		assertTrue(FastSkipDynamicTableNameInnerInterceptor.containsIgnoreCase("from sys_log", "SYS_LOG"));
		assertFalse(FastSkipDynamicTableNameInnerInterceptor.containsIgnoreCase("from sys_lo", "sys_log"));
		assertFalse(FastSkipDynamicTableNameInnerInterceptor.containsIgnoreCase("log", "sys_log"));
		assertTrue(FastSkipDynamicTableNameInnerInterceptor.containsIgnoreCase("sys_log", "sys_log"));
		// 非 ASCII 表名：精确包含必须判为包含
		assertTrue(FastSkipDynamicTableNameInnerInterceptor.containsIgnoreCase("FROM İtem_log", "İtem_log"));
	}

	private long skippedCount() {
		return counter(fast, "skipped");
	}

	private static long counter(FastSkipDynamicTableNameInnerInterceptor interceptor, String field) {
		Object adder = ReflectionTestUtils.getField(interceptor, field);
		return ((java.util.concurrent.atomic.LongAdder) adder).sum();
	}

}
