package com.zhiflow.mall.admin.config;

import com.zhiflow.mall.common.orderno.RegistryWorkerIdAssigner;
import com.zhiflow.mall.common.orderno.WorkerIdRegistryProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CARD-01 真库验证：mall-api / mall-admin 启动时抢订单号机器位，表已在的情况下用应用专用账号
 * （没有 public 的 CREATE、也不是表的属主）启动，<b>不发任何 DDL</b>——否则 PG 每次启动记两条
 * permission denied / must be owner 的 ERROR。前提与跑法同 {@link MallTenantProvisionAsAppAccountPgTest}
 * （额外要 {@code -Dddlrole.pg.api-password}）。
 */
@EnabledIfSystemProperty(named = "ddlrole.pg.url", matches = ".+")
class WorkerIdBootstrapAsAppAccountPgTest {

	@Test
	void appAccountStartupIssuesNoDdlWhenTableExists() throws Exception {
		HikariConfig cfg = new HikariConfig();
		cfg.setJdbcUrl(System.getProperty("ddlrole.pg.url"));
		cfg.setUsername("app_mall_api");
		cfg.setPassword(System.getProperty("ddlrole.pg.api-password"));
		cfg.setMaximumPoolSize(2);
		List<String> prepared = new CopyOnWriteArrayList<>();
		try (HikariDataSource pool = new HikariDataSource(cfg)) {
			DelegatingDataSource recording = new DelegatingDataSource(pool) {
				@Override
				public Connection getConnection() throws java.sql.SQLException {
					Connection real = super.getConnection();
					return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class },
							(proxy, method, args) -> {
								if (method.getName().startsWith("prepare") && args != null && args[0] instanceof String sql) {
									prepared.add(sql.strip());
								}
								try {
									return method.invoke(real, args);
								}
								catch (java.lang.reflect.InvocationTargetException e) {
									throw e.getCause();
								}
							});
				}
			};
			try (RegistryWorkerIdAssigner assigner = new RegistryWorkerIdAssigner(recording,
					new WorkerIdRegistryProperties(), "card01-test")) {
				assertTrue(assigner.getWorkerId() >= 0);
			}
		}
		assertTrue(prepared.stream().noneMatch(s -> s.toUpperCase().startsWith("CREATE")),
				"表和索引都在时不应再发 DDL，实际: " + prepared);
		assertTrue(prepared.stream().anyMatch(s -> s.contains("ON CONFLICT (slot_id) DO NOTHING")), "槽位预填照常执行");
	}
}
