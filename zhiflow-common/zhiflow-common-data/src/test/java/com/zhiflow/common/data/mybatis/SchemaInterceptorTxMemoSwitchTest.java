package com.zhiflow.common.data.mybatis;

import com.zhiflow.common.data.shardingdatabase.config.ShardingDataBaseProperties;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 事务内复用 search_path 记忆（A3）只受 {@code memoize} 一个开关控制（2026-10-23 起不再有单独的事务内开关）。
 *
 * <p>不连库：池代理连接每次借出是新实例，unwrap 返回同一个"物理连接"，只记录发出的 SET 文本。
 * 真 PG + HikariCP 下的同类场景见 {@link SchemaInterceptorMemoTest} #11/#12（默认跳过）。
 */
class SchemaInterceptorTxMemoSwitchTest {

	private static final String SET_A = "SET search_path TO zhiflow_mall_1, public";

	private static final String SET_B = "SET search_path TO zhiflow_mall_2, public";

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final List<String> sets = new ArrayList<>();

	/** 被池代理包住的"物理连接"：只用作记忆键（WeakHashMap 只调 hashCode / equals，按对象身份）。 */
	private final Connection physical = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
			new Class<?>[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
				case "hashCode" -> System.identityHashCode(p);
				case "equals" -> p == a[0];
				case "toString" -> "physical";
				default -> throw new UnsupportedOperationException(m.getName());
			});

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
	}

	@Test
	void memoizeOn_txReusesCommittedMemo_untilTxNeedsDifferentPath() {
		SchemaInterceptor si = interceptor(true);

		run(si, "1", true);   // autoCommit：SET 并写记忆
		run(si, "1", false);  // 事务内同值：命中记忆跳过
		run(si, "1", false);
		run(si, "2", false);  // 事务内换值：SET 并删记忆
		run(si, "1", false);  // 记忆已删：本事务剩余语句照旧 SET
		run(si, "1", true);   // 事务后首条 autoCommit：必然重设并写回记忆
		run(si, "1", false);  // 新事务又能命中

		assertEquals(List.of(SET_A, SET_B, SET_A, SET_A), sets);
		assertEquals(3, count(true, "hit"));
		assertEquals(2, count(true, "set"));
		assertEquals(0, count(false, "hit"));
		assertEquals(2, count(false, "set"));
	}

	@Test
	void memoizeOn_txWithoutMemo_setsEveryStatement() {
		SchemaInterceptor si = interceptor(true);

		run(si, "1", false);
		run(si, "1", false);
		run(si, "1", false);

		assertEquals(List.of(SET_A, SET_A, SET_A), sets, "事务内绝不写记忆：没有已提交记忆时每条都 SET");
		assertEquals(0, count(true, "hit"));
	}

	@Test
	void memoizeOff_setsEveryStatement_autoCommitAndTx() {
		SchemaInterceptor si = interceptor(false);

		run(si, "1", true);
		run(si, "1", true);
		run(si, "1", false);
		run(si, "1", false);

		assertEquals(List.of(SET_A, SET_A, SET_A, SET_A), sets, "memoize 关 = 逐条 SET（事务内也一样）");
		assertEquals(0, count(true, "hit") + count(false, "hit"));
	}

	@Test
	void memoizeDefaultIsOff() {
		assertEquals(Boolean.FALSE, new ShardingDataBaseProperties().getSchemaInterceptorMemoize(),
				"memoize 默认关，回退手段不变");
	}

	// ------------------------------------------------------------------ 工具

	private SchemaInterceptor interceptor(boolean memoize) {
		ShardingDataBaseProperties p = new ShardingDataBaseProperties();
		p.setDbname("zhiflow_mall");
		p.setDefaultTenantId("1");
		p.setSchemaInterceptorMemoize(memoize);
		DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
		bf.registerSingleton("meterRegistry", registry);
		return new SchemaInterceptor(p, bf.getBeanProvider(MeterRegistry.class));
	}

	private void run(SchemaInterceptor si, String tenant, boolean autoCommit) {
		TenantContextHolder.setTenantId(tenant);
		si.beforePrepare(null, pooled(autoCommit), null);
	}

	private double count(boolean tx, String result) {
		Counter c = registry.find(SchemaInterceptor.SEARCH_PATH_METRIC)
				.tag("tx", String.valueOf(tx)).tag("result", result).counter();
		return c == null ? 0 : c.count();
	}

	/** 池代理：每次借出新实例，unwrap 剥到同一个物理连接；createStatement 只记录 SET 文本。 */
	private Connection pooled(boolean autoCommit) {
		InvocationHandler statement = (p, m, a) -> switch (m.getName()) {
			case "execute" -> {
				sets.add((String) a[0]);
				yield false;
			}
			case "close" -> null;
			default -> throw new UnsupportedOperationException(m.getName());
		};
		return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
				(p, m, a) -> switch (m.getName()) {
					case "getAutoCommit" -> autoCommit;
					case "unwrap" -> physical;
					case "createStatement" -> Proxy.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[]{Statement.class}, statement);
					default -> throw new UnsupportedOperationException(m.getName());
				});
	}
}
