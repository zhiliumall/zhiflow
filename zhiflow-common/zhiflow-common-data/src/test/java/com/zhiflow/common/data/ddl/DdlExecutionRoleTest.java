package com.zhiflow.common.data.ddl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CARD-01：运行时 DDL 的执行身份。核心要求两条——开关关时<b>一个字节都不变</b>；开关开时提升过的身份
 * <b>不留在连接池里</b>（Flyway 走物理连接，事务内走 SET LOCAL）。
 */
class DdlExecutionRoleTest {

	private static final DdlExecutionRole.ConnectionSpec SPEC = new DdlExecutionRole.ConnectionSpec(
			"jdbc:postgresql://127.0.0.1:5432/zhiflow", "app_mall_admin", "pw");

	private static DdlExecutionRole role(MockEnvironment env, DdlExecutionRole.ConnectionOpener opener) {
		return new DdlExecutionRole(env, () -> SPEC, () -> null, opener);
	}

	private static MockEnvironment on() {
		return new MockEnvironment().withProperty(DdlExecutionRole.ENABLED_PROPERTY, "true");
	}

	@Test
	@DisplayName("开关缺失：原样返回连接池，不开任何物理连接")
	void offReturnsPooledUntouched() {
		AtomicInteger opened = new AtomicInteger();
		DdlExecutionRole r = role(new MockEnvironment(), spec -> {
			opened.incrementAndGet();
			return mock(Connection.class);
		});
		DataSource pooled = mock(DataSource.class);

		assertThat(r.isEnabled()).isFalse();
		assertThat(r.dataSourceFor(pooled)).isSameAs(pooled);
		assertThat(opened).hasValue(0);
		verifyNoInteractions(pooled);
	}

	@Test
	@DisplayName("开关值写错：按关处理")
	void badFlagValueIsOff() {
		DdlExecutionRole r = role(new MockEnvironment().withProperty(DdlExecutionRole.ENABLED_PROPERTY, "yes-please"),
				spec -> mock(Connection.class));
		DataSource pooled = mock(DataSource.class);

		assertThat(r.isEnabled()).isFalse();
		assertThat(r.dataSourceFor(pooled)).isSameAs(pooled);
	}

	@Test
	@DisplayName("开关关：setLocalRole 不碰连接")
	void offSetLocalRoleIsNoop() throws SQLException {
		Connection c = mock(Connection.class);
		role(new MockEnvironment(), spec -> c).setLocalRole(c);
		verifyNoInteractions(c);
	}

	@Test
	@DisplayName("开关开：每次新开物理连接并 SET ROLE gp_migrator，关闭即物理关闭")
	void onOpensPhysicalConnectionAndSetsRole() throws SQLException {
		Connection physical = mock(Connection.class);
		Statement st = mock(Statement.class);
		when(physical.createStatement()).thenReturn(st);
		AtomicInteger opened = new AtomicInteger();
		DdlExecutionRole r = role(on(), spec -> {
			assertThat(spec).isEqualTo(SPEC);
			opened.incrementAndGet();
			return physical;
		});
		DataSource pooled = mock(DataSource.class);

		DataSource ddl = r.dataSourceFor(pooled);
		assertThat(ddl).isNotSameAs(pooled);
		try (Connection c = ddl.getConnection()) {
			assertThat(c).isSameAs(physical);
		}
		assertThat(opened).hasValue(1);
		verify(st).execute("SET ROLE gp_migrator");
		verify(physical).close();
		verifyNoInteractions(pooled);
	}

	@Test
	@DisplayName("开关开：SET ROLE 失败要关掉物理连接并把错误抛出去")
	void setRoleFailureClosesConnection() throws SQLException {
		Connection physical = mock(Connection.class);
		Statement st = mock(Statement.class);
		when(physical.createStatement()).thenReturn(st);
		doThrow(new SQLException("permission denied to set role \"gp_migrator\"")).when(st).execute(anyString());
		DataSource ddl = role(on(), spec -> physical).dataSourceFor(mock(DataSource.class));

		assertThatThrownBy(ddl::getConnection).isInstanceOf(SQLException.class)
			.hasMessageContaining("permission denied to set role");
		verify(physical).close();
	}

	@Test
	@DisplayName("开关开：事务里发 SET LOCAL ROLE；自动提交时拒绝（PG 会静默忽略）")
	void onSetLocalRoleRequiresTransaction() throws SQLException {
		DdlExecutionRole r = role(on(), spec -> mock(Connection.class));

		Connection inTx = mock(Connection.class);
		Statement st = mock(Statement.class);
		when(inTx.getAutoCommit()).thenReturn(false);
		when(inTx.createStatement()).thenReturn(st);
		r.setLocalRole(inTx);
		verify(st).execute("SET LOCAL ROLE gp_migrator");

		Connection autoCommit = mock(Connection.class);
		when(autoCommit.getAutoCommit()).thenReturn(true);
		assertThatThrownBy(() -> r.setLocalRole(autoCommit)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("事务");
		verify(autoCommit, never()).createStatement();
	}

	@Test
	@DisplayName("开关开：不在 Spring 事务里调用 setLocalRoleInCurrentTransaction 直接报错；关时无事")
	void currentTransactionVariant() {
		assertThatThrownBy(() -> role(on(), spec -> mock(Connection.class)).setLocalRoleInCurrentTransaction())
			.isInstanceOf(IllegalStateException.class);
		role(new MockEnvironment(), spec -> mock(Connection.class)).setLocalRoleInCurrentTransaction();
		DdlExecutionRole.disabled().setLocalRoleInCurrentTransaction();
	}

	@Test
	@DisplayName("角色名可配，但非法标识符回落默认值，不拼进 SQL")
	void roleNameValidated() {
		assertThat(role(on().withProperty(DdlExecutionRole.ROLE_PROPERTY, "gp_ddl"), s -> null).role())
			.isEqualTo("gp_ddl");
		assertThat(role(on().withProperty(DdlExecutionRole.ROLE_PROPERTY, "gp_migrator; drop table x"), s -> null)
			.role()).isEqualTo(DdlExecutionRole.DEFAULT_ROLE);
		assertThat(role(on().withProperty(DdlExecutionRole.ROLE_PROPERTY, "GP_Migrator"), s -> null).role())
			.isEqualTo(DdlExecutionRole.DEFAULT_ROLE);
	}

	@Test
	@DisplayName("disabled() 永远关")
	void disabledInstance() {
		DataSource pooled = mock(DataSource.class);
		assertThat(DdlExecutionRole.disabled().isEnabled()).isFalse();
		assertThat(DdlExecutionRole.disabled().dataSourceFor(pooled)).isSameAs(pooled);
	}

	@Test
	@DisplayName("10-18 迁移数据源·开关关：也不经连接池——新开物理连接、不切身份，关闭即物理关闭")
	void migrationDataSourceOffIsUnpooledWithoutRole() throws SQLException {
		Connection physical = mock(Connection.class);
		AtomicInteger opened = new AtomicInteger();
		DdlExecutionRole r = role(new MockEnvironment(), spec -> {
			assertThat(spec).isEqualTo(SPEC);
			opened.incrementAndGet();
			return physical;
		});

		DataSource migration = r.migrationDataSource();
		try (Connection c = migration.getConnection()) {
			assertThat(c).isSameAs(physical);
		}
		try (Connection c = migration.getConnection()) {
			assertThat(c).isSameAs(physical);
		}
		assertThat(opened).as("每次 getConnection 都新开物理连接").hasValue(2);
		verify(physical, never()).createStatement();
		verify(physical, times(2)).close();
	}

	@Test
	@DisplayName("10-18 迁移数据源·开关开：与 dataSourceFor 一样物理连接 + SET ROLE")
	void migrationDataSourceOnSwitchesRole() throws SQLException {
		Connection physical = mock(Connection.class);
		Statement st = mock(Statement.class);
		when(physical.createStatement()).thenReturn(st);
		DataSource migration = role(on(), spec -> physical).migrationDataSource();

		try (Connection c = migration.getConnection()) {
			assertThat(c).isSameAs(physical);
		}
		verify(st).execute("SET ROLE gp_migrator");
		verify(physical).close();
	}

	@Test
	@DisplayName("10-18 unpooledDataSource：开关开也不切身份（只读核实用应用账号）")
	void unpooledDataSourceNeverSwitchesRole() throws SQLException {
		Connection physical = mock(Connection.class);
		try (Connection c = role(on(), spec -> physical).unpooledDataSource().getConnection()) {
			assertThat(c).isSameAs(physical);
		}
		verify(physical, never()).createStatement();
		verify(physical).close();
	}

	@Test
	@DisplayName("10-18 迁移数据源：取不到连接信息直接报错，绝不回落到连接池")
	void migrationDataSourceWithoutSpecFailsClosed() {
		DataSource pooled = mock(DataSource.class);
		DdlExecutionRole r = new DdlExecutionRole(new MockEnvironment(), () -> {
			throw new IllegalStateException("没有 spring.datasource.url");
		}, () -> pooled);

		assertThatThrownBy(r::migrationDataSource).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(r::unpooledDataSource).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(DdlExecutionRole.disabled()::migrationDataSource).isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(pooled);
	}

	@Test
	@DisplayName("单数据源：物理连接信息取 spring.datasource.*，嵌套占位符按解析后的值")
	void resolveSpecFromSingleDataSource() {
		MockEnvironment env = new MockEnvironment()
			.withProperty("PG_PASSWORD", "s3cret")
			.withProperty("spring.datasource.url", "jdbc:postgresql://pg:5432/zhiflow?ApplicationName=${spring.application.name}")
			.withProperty("spring.application.name", "zhiflow-boot")
			.withProperty("spring.datasource.username", "postgres")
			.withProperty("spring.datasource.password", "${PG_PASSWORD}");

		DdlExecutionRole.ConnectionSpec spec = DdlExecutionRoleAutoConfiguration.resolveSpec(env);

		assertThat(spec.url()).isEqualTo("jdbc:postgresql://pg:5432/zhiflow?ApplicationName=zhiflow-boot");
		assertThat(spec.username()).isEqualTo("postgres");
		assertThat(spec.password()).isEqualTo("s3cret");
	}

	@Test
	@DisplayName("没配 spring.datasource.url：报错说明原因，不悄悄退回连接池")
	void resolveSpecWithoutUrlFailsClosed() {
		assertThatThrownBy(() -> DdlExecutionRoleAutoConfiguration.resolveSpec(new MockEnvironment()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("spring.datasource.url");
	}
}
