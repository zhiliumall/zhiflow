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
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无租户 SQL 的「只记录」模式（2026-09-27 数据库方案第 6 步第 4 块）。
 *
 * <p>钉住：默认 off 与历史行为一致（不记、不执行任何语句）；log 只在租户分库连接上记、照常放行；
 * 记录本身不在连接上执行任何语句、出错不影响业务；语句 id 标签有上限。
 */
class SchemaInterceptorNoTenantRecordTest {

	private static final String MALL_URL = "jdbc:postgresql://pg:5432/zhiflow?charSet=utf8";

	private static final String UPMS_URL = "jdbc:postgresql://pg:5432/zhiflow?currentSchema=zhiflow_upms";

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final List<String> calls = new ArrayList<>();

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
	}

	private SchemaInterceptor interceptor(String mode) {
		ShardingDataBaseProperties p = new ShardingDataBaseProperties();
		p.setDbname("zhiflow_mall");
		p.setNoTenantMode(mode);
		DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
		bf.registerSingleton("meterRegistry", registry);
		return new SchemaInterceptor(p, bf.getBeanProvider(MeterRegistry.class));
	}

	private double count(String statement) {
		Counter c = registry.find(SchemaInterceptor.NO_TENANT_METRIC).tag("statement", statement).counter();
		return c == null ? 0 : c.count();
	}

	@Test
	void offIsTheDefaultAndRecordsNothing() {
		assertEquals("off", new ShardingDataBaseProperties().getNoTenantMode(), "默认必须是 off：合进 master 不改变任何行为");
		interceptor("off").beforePrepare(null, connection(MALL_URL), null);
		assertNull(registry.find(SchemaInterceptor.NO_TENANT_METRIC).counter());
		assertTrue(calls.isEmpty(), "off 时连连接都不碰：" + calls);
	}

	@Test
	void logCountsOnTenantShardedConnectionWithoutRunningSql() {
		SchemaInterceptor si = interceptor("log");
		si.beforePrepare(null, connection(MALL_URL), null);
		si.beforePrepare(null, connection(MALL_URL), null);
		assertEquals(2, count("unknown"));
		assertTrue(calls.stream().noneMatch(c -> c.startsWith("createStatement") || c.startsWith("prepare")),
				"记录时不得在连接上执行任何语句：" + calls);
	}

	@Test
	void logDoesNotTouchStatementsThatHaveATenant() {
		TenantContextHolder.setTenantId("1");
		interceptor("log").beforePrepare(null, connection(MALL_URL), null);
		assertNull(registry.find(SchemaInterceptor.NO_TENANT_METRIC).counter());
		assertTrue(calls.contains("createStatement"), "有租户号时照常 SET search_path：" + calls);
	}

	@Test
	void recordingFailureNeverBreaksTheQuery() {
		Connection broken = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class },
				(proxy, method, args) -> {
					throw new IllegalStateException("元数据坏了");
				});
		assertDoesNotThrow(() -> interceptor("log").beforePrepare(null, broken, null));
	}

	@Test
	void rejectIsNotImplementedYetAndBehavesLikeLog() {
		assertDoesNotThrow(() -> interceptor("reject").beforePrepare(null, connection(MALL_URL), null));
		assertEquals(1, count("unknown"));
	}

	@Test
	void statementTagsAreCapped() throws Exception {
		SchemaInterceptor si = interceptor("log");
		java.lang.reflect.Field f = SchemaInterceptor.class.getDeclaredField("noTenantSeen");
		f.setAccessible(true);
		@SuppressWarnings("unchecked")
		java.util.Set<String> seen = (java.util.Set<String>) f.get(si);
		for (int i = 0; i < SchemaInterceptor.MAX_NO_TENANT_STATEMENTS; i++) {
			seen.add("stmt" + i);
		}
		si.beforePrepare(null, connection(MALL_URL), null);
		assertEquals(1, count("other"), "超过上限的新语句记成 other");
		assertEquals(0, count("unknown"));
	}

	/** 记下被调用的连接方法；只回答 getMetaData().getURL() 与 createStatement()。 */
	private Connection connection(String url) {
		DatabaseMetaData meta = (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { DatabaseMetaData.class },
				(InvocationHandler) (proxy, method, args) -> "getURL".equals(method.getName()) ? url : defaultValue(method));
		java.sql.Statement stmt = (java.sql.Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { java.sql.Statement.class }, (proxy, method, args) -> defaultValue(method));
		return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class },
				(InvocationHandler) (proxy, method, args) -> {
					calls.add(method.getName());
					return switch (method.getName()) {
						case "getMetaData" -> meta;
						case "createStatement" -> stmt;
						case "getAutoCommit" -> true;
						default -> defaultValue(method);
					};
				});
	}

	private static Object defaultValue(Method method) {
		Class<?> t = method.getReturnType();
		if (!t.isPrimitive()) {
			return null;
		}
		if (t == boolean.class) {
			return false;
		}
		if (t == void.class) {
			return null;
		}
		return 0;
	}
}
