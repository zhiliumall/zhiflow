package com.zhiflow.common.test.spike;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S8 波次 0 · 软删翻转前的行为实测。
 *
 * <p><b>这不是回归测试，是一次实验的可复现记录。</b>
 *
 * <p>要回答三个不许靠推断的问题：
 * <ol>
 *   <li>{@code updateById} 会不会把逻辑删字段排除出 SET 子句
 *       —— 决定 S9 注销墓碑路径（{@code UserInfoServiceImpl:1220}
 *       先 {@code setDelFlag("1")} 再 update）在翻转后还成不成立；</li>
 *   <li>{@code @TableLogic(value="0", delval="1")} 对 {@code String} 型 delFlag 的比较是否如预期；</li>
 *   <li>PG 部分唯一索引 {@code WHERE del_flag='0'} 对「墓碑行 + 同自然键新行」的实际放行行为
 *       —— 这是购物车合并（B 桶）能否活下来的前提。</li>
 * </ol>
 *
 * <p>表结构照抄 {@code shopping_cart} 的关键部分：{@code del_flag} 是
 * {@code varchar(1)}，唯一键是 {@code (tenant_id, user_id, sku_id)}。
 *
 * <p><b>Docker 不可用时自动跳过</b>，与 {@link PostgresContainerSpikeTest} 同姿态。
 */
class S8SoftDeleteSpikeTest {

	/** 对齐部署的大版本 */
	private static final String IMAGE = "postgres:18-alpine";

	private static PostgreSQLContainer<?> container;

	private static SqlSessionFactory sqlSessionFactory;

	private static DataSource dataSource;

	private static final SqlRecorder RECORDER = new SqlRecorder();

	@BeforeAll
	static void startContainer() throws SQLException {
		Assumptions.assumeTrue(dockerAvailable(), "Docker 未启动，跳过（open -a Docker 后重跑）");

		container = new PostgreSQLContainer<>(IMAGE);
		container.start();

		PGSimpleDataSource ds = new PGSimpleDataSource();
		ds.setUrl(container.getJdbcUrl());
		ds.setUser(container.getUsername());
		ds.setPassword(container.getPassword());
		dataSource = ds;

		try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
			s.execute("""
					create table spike_cart (
					    id          varchar(64) primary key,
					    tenant_id   varchar(64),
					    user_id     varchar(64),
					    sku_id      varchar(64),
					    quantity    int,
					    del_flag    varchar(1) default '0',
					    update_time timestamp
					)""");
			// 当时 shopping_cart 的现状：唯一索引不含 del_flag。先按现状建，测完再换成部分索引。
			s.execute("create unique index uk_spike_cart_full "
					+ "on spike_cart (tenant_id, user_id, sku_id)");
		}

		MybatisConfiguration configuration = new MybatisConfiguration();
		configuration.setEnvironment(
				new Environment("s8-spike", new JdbcTransactionFactory(), dataSource));
		configuration.addInterceptor(RECORDER);
		configuration.addMapper(SpikeCartMapper.class);
		// 对齐生产：全局 MetaObjectHandler 会往每条 update 的 SET 里塞 update_time。
		// 少了它 Q1c/Q1d 会得出「SET 变空 → 500」这个真应用里不成立的结论。
		com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(configuration)
				.setMetaObjectHandler(new SpikeMetaObjectHandler());
		sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
	}

	@AfterAll
	static void stopContainer() {
		if (container != null) {
			container.stop();
		}
	}

	@BeforeEach
	void reset() throws SQLException {
		RECORDER.sqls.clear();
		try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
			s.execute("truncate table spike_cart");
		}
	}

	// ─────────────────────────────── 问题 1 & 2 ───────────────────────────────

	@Test
	@DisplayName("Q2 · deleteById 被改写成 UPDATE，行还在库里，但 MP 自动查询看不见")
	void deleteByIdBecomesUpdate() throws SQLException {
		insert("c1", "t1", "u1", "sku1", 2);

		try (SqlSession session = sqlSessionFactory.openSession(true)) {
			SpikeCartMapper mapper = session.getMapper(SpikeCartMapper.class);
			assertEquals(1, mapper.deleteById("c1"));

			// 行没被物理删掉
			assertEquals("1", rawDelFlag("c1"));
			assertEquals(1, rawCount("select count(*) from spike_cart"));

			// 但 MP 自动查询看不到了
			assertNull(mapper.selectById("c1"));
			assertTrue(mapper.selectList(Wrappers.emptyWrapper()).isEmpty());
		}

		String deleteSql = firstSqlContaining("spike_cart");
		System.out.println("\n[Q2] deleteById 生成的 SQL：\n    " + deleteSql);
		assertTrue(deleteSql.toUpperCase().startsWith("UPDATE"),
				"deleteById 应被改写成 UPDATE，实际：" + deleteSql);
		assertTrue(deleteSql.contains("del_flag='1'") || deleteSql.contains("del_flag = '1'")
						|| deleteSql.contains("del_flag=?") || deleteSql.contains("del_flag = ?"),
				"UPDATE 应把 del_flag 置为删除值，实际：" + deleteSql);
	}

	@Test
	@DisplayName("Q1 · updateById 会不会把逻辑删字段排除出 SET —— 决定 S9 墓碑路径存亡")
	void updateByIdWithLogicDeleteField() throws SQLException {
		insert("c1", "t1", "u1", "sku1", 2);

		try (SqlSession session = sqlSessionFactory.openSession(true)) {
			SpikeCartMapper mapper = session.getMapper(SpikeCartMapper.class);

			// 模拟 S9 注销墓碑：改业务字段的同时把 delFlag 置 1
			SpikeCart entity = new SpikeCart();
			entity.setId("c1");
			entity.setUserId("");                 // 墓碑：清掉标识信息
			entity.setDelFlag("1");               // 墓碑：标记已删
			int affected = mapper.updateById(entity);

			String updateSql = firstSqlContaining("update spike_cart");
			System.out.println("\n[Q1] updateById(含 delFlag=\"1\") 生成的 SQL：\n    " + updateSql);
			System.out.println("[Q1] 影响行数 = " + affected);
			System.out.println("[Q1] 落库后 del_flag = " + rawDelFlag("c1")
					+ " / user_id = '" + rawUserId("c1") + "'");

			boolean setIncludesDelFlag = updateSql.toUpperCase().indexOf("WHERE") > 0
					&& updateSql.substring(0, updateSql.toUpperCase().indexOf("WHERE"))
					.contains("del_flag");
			System.out.println("[Q1] SET 子句是否包含 del_flag = " + setIncludesDelFlag);

			// 记录事实即可，断言只钉住「这次 update 确实命中了行」
			assertEquals(1, affected, "首次 update 应命中未删除的行");
		}
	}

	@Test
	@DisplayName("Q1c · 手工软删的两种写法对比：updateById 失效，LambdaUpdateWrapper.set 有效")
	void manualSoftDeleteWritePaths() throws SQLException {
		insert("c1", "t1", "u1", "sku1", 2);
		insert("c2", "t1", "u1", "sku2", 2);

		try (SqlSession session = sqlSessionFactory.openSession(true)) {
			SpikeCartMapper mapper = session.getMapper(SpikeCartMapper.class);

			// 写法 A（现存代码用的）：实体 setDelFlag + updateById
			String errA = null;
			try {
				SpikeCart entity = new SpikeCart();
				entity.setId("c1");
				entity.setDelFlag("1");
				mapper.updateById(entity);
			}
			catch (Exception e) {
				errA = rootMessage(e);
			}
			String afterA = rawDelFlag("c1");

			// 写法 B：LambdaUpdateWrapper 显式 set（Lambda 指向继承自基类的 getter）
			String errB = null;
			try {
				mapper.update(null, Wrappers.<SpikeCart>lambdaUpdate()
						.eq(SpikeCart::getId, "c2")
						.set(SpikeCart::getDelFlag, "1"));
			}
			catch (Exception e) {
				errB = rootMessage(e);
			}
			String afterB = rawDelFlag("c2");

			// 写法 C：UpdateWrapper 直接写列名，完全绕开 Lambda 解析
			String errC = null;
			try {
				mapper.update(null, Wrappers.<SpikeCart>update()
						.eq("id", "c2")
						.set("del_flag", "1"));
			}
			catch (Exception e) {
				errC = rootMessage(e);
			}
			String afterC = rawDelFlag("c2");

			System.out.println("""

					[Q1c] 三种手工软删写法实测（实体只 set 了 delFlag，模拟纯软删）
					    A · setDelFlag("1") + updateById       → del_flag = %s%s
					    B · LambdaUpdateWrapper.set(Lambda)    → del_flag = %s%s
					    C · UpdateWrapper.set("del_flag", …)   → del_flag = %s%s
					""".formatted(
					afterA, errA == null ? "" : "   ⚠ " + errA,
					afterB, errB == null ? "" : "   ⚠ " + errB,
					afterC, errC == null ? "" : "   ⚠ " + errC));
			System.out.println("[Q1c] 本用例记录到的全部 SQL：");
			RECORDER.sqls.forEach(s -> System.out.println("    " + s));

			assertEquals("0", afterA,
					"写法 A 应失效 —— MP 把逻辑删字段排除出 updateById 的 SET 子句");
			assertNull(errA, "有 MetaObjectHandler 兜底 update_time 后，写法 A 不再报错，而是静默失效");
		}
	}

	@Test
	@DisplayName("Q1d · update(实体, wrapper) 形态 —— FlashSaleActivityServiceImpl:114 的写法")
	void updateWithEntityAndWrapper() throws SQLException {
		insert("c1", "t1", "u1", "sku1", 2);

		try (SqlSession session = sqlSessionFactory.openSession(true)) {
			SpikeCartMapper mapper = session.getMapper(SpikeCartMapper.class);
			SpikeCart onlyDelFlag = new SpikeCart();
			onlyDelFlag.setDelFlag("1");

			String err = null;
			try {
				mapper.update(onlyDelFlag,
						Wrappers.<SpikeCart>update().lambda().in(SpikeCart::getId, List.of("c1")));
			}
			catch (Exception e) {
				err = rootMessage(e);
			}
			System.out.println("\n[Q1d] update(实体只含 delFlag, wrapper) → del_flag = " + rawDelFlag("c1")
					+ (err == null ? "" : "   ⚠ " + err));
			assertEquals("0", rawDelFlag("c1"), "实体侧的 delFlag 同样被剥掉 —— 这个写法翻转后也失效");
		}
	}

	@Test
	@DisplayName("Q1e · 推荐修法：先 updateById 改业务字段，再 removeById 打删除标记（顺序不可颠倒）")
	void recommendedFixOrdering() throws SQLException {
		insert("c1", "t1", "u1", "sku1", 2);
		insert("c2", "t1", "u1", "sku2", 2);

		try (SqlSession session = sqlSessionFactory.openSession(true)) {
			SpikeCartMapper mapper = session.getMapper(SpikeCartMapper.class);

			// 正确顺序：先改业务字段，再软删
			SpikeCart tombstone = new SpikeCart();
			tombstone.setId("c1");
			tombstone.setUserId("");                 // 墓碑：清标识
			assertEquals(1, mapper.updateById(tombstone));
			assertEquals(1, mapper.deleteById("c1"));
			assertEquals("1", rawDelFlag("c1"));
			assertEquals("", rawUserId("c1"));

			// 颠倒顺序：先软删，再改业务字段 → 第二步被 WHERE del_flag='0' 挡掉
			assertEquals(1, mapper.deleteById("c2"));
			SpikeCart late = new SpikeCart();
			late.setId("c2");
			late.setUserId("");
			int affected = mapper.updateById(late);

			System.out.println("""

					[Q1e] 顺序敏感性
					    先 update 后 delete → del_flag=%s, user_id='%s'   ✓
					    先 delete 后 update → update 影响行数=%d（被 WHERE del_flag='0' 挡掉），user_id 仍是 '%s'
					""".formatted(rawDelFlag("c1"), rawUserId("c1"), affected, rawUserId("c2")));

			assertEquals(0, affected, "软删之后再改业务字段会静默失败 —— 顺序不可颠倒");
			assertEquals("u1", rawUserId("c2"), "颠倒顺序时业务字段没被改到");
		}
	}

	@Test
	@DisplayName("Q1b · 对已墓碑行再 update —— MP 会不会因 WHERE del_flag='0' 静默 no-op")
	void updateAlreadyTombstonedRow() throws SQLException {
		insert("c1", "t1", "u1", "sku1", 2);
		try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
			s.execute("update spike_cart set del_flag = '1' where id = 'c1'");
		}

		try (SqlSession session = sqlSessionFactory.openSession(true)) {
			SpikeCartMapper mapper = session.getMapper(SpikeCartMapper.class);
			SpikeCart entity = new SpikeCart();
			entity.setId("c1");
			entity.setQuantity(99);
			int affected = mapper.updateById(entity);

			System.out.println("\n[Q1b] 对已墓碑行 updateById，影响行数 = " + affected
					+ "（0 = MP 追加了 WHERE del_flag='0' 把它挡掉）");
			System.out.println("[Q1b] SQL：\n    " + firstSqlContaining("update spike_cart"));
			assertEquals(0, affected, "已墓碑行应被 WHERE del_flag='0' 挡住 —— 这就是「静默 no-op」风险");
		}
	}

	@Test
	@DisplayName("Q2b · selectById / selectList 自动追加 del_flag 条件")
	void selectAutoFiltersDeleted() throws SQLException {
		insert("c1", "t1", "u1", "sku1", 1);
		insert("c2", "t1", "u1", "sku2", 1);
		try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
			s.execute("update spike_cart set del_flag = '1' where id = 'c2'");
		}

		try (SqlSession session = sqlSessionFactory.openSession(true)) {
			SpikeCartMapper mapper = session.getMapper(SpikeCartMapper.class);
			assertNotNull(mapper.selectById("c1"));
			assertNull(mapper.selectById("c2"));
			assertEquals(1, mapper.selectList(Wrappers.emptyWrapper()).size());

			String selectSql = firstSqlContaining("select");
			System.out.println("\n[Q2b] selectById 生成的 SQL：\n    " + selectSql);
			assertTrue(selectSql.contains("del_flag"),
					"selectById 应追加 del_flag 条件，实际：" + selectSql);
		}
	}

	// ─────────────────────────────── 问题 3 ───────────────────────────────

	@Test
	@DisplayName("Q3a · 现状唯一索引（不含 del_flag）下，软删后重插同自然键 —— 必然冲突")
	void fullUniqueIndexBreaksSoftDeleteReinsert() throws SQLException {
		insert("c1", "t1", "u1", "sku1", 2);

		try (SqlSession session = sqlSessionFactory.openSession(true)) {
			SpikeCartMapper mapper = session.getMapper(SpikeCartMapper.class);
			mapper.deleteById("c1");        // 软删，墓碑仍占 (t1,u1,sku1)

			SpikeCart merged = new SpikeCart();
			merged.setId("c2");
			merged.setTenantId("t1");
			merged.setUserId("u1");
			merged.setSkuId("sku1");
			merged.setQuantity(5);

			// 这正是 ShoppingCartServiceImpl.save 的「删旧行 → 立刻插新行」
			Exception e = assertThrows(Exception.class, () -> mapper.insert(merged));
			System.out.println("\n[Q3a] 全量唯一索引 + 软删后重插 → " + rootMessage(e));
		}
	}

	@Test
	@DisplayName("Q3b · 换成部分唯一索引 WHERE del_flag='0' 后，同一序列放行")
	void partialUniqueIndexAllowsReinsert() throws SQLException {
		try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
			s.execute("drop index uk_spike_cart_full");
			s.execute("create unique index uk_spike_cart_alive on spike_cart "
					+ "(tenant_id, user_id, sku_id) where del_flag = '0'");
		}
		try {
			insert("c1", "t1", "u1", "sku1", 2);

			try (SqlSession session = sqlSessionFactory.openSession(true)) {
				SpikeCartMapper mapper = session.getMapper(SpikeCartMapper.class);
				mapper.deleteById("c1");

				SpikeCart merged = new SpikeCart();
				merged.setId("c2");
				merged.setTenantId("t1");
				merged.setUserId("u1");
				merged.setSkuId("sku1");
				merged.setQuantity(5);
				assertEquals(1, mapper.insert(merged), "部分唯一索引下重插应成功");

				// 墓碑与活行共存
				assertEquals(2, rawCount("select count(*) from spike_cart"));
				assertEquals(1, mapper.selectList(Wrappers.emptyWrapper()).size());
				System.out.println("\n[Q3b] 部分唯一索引：墓碑 + 活行共存，MP 只看得到活行 ✓");

				// 仍然拦得住「两个活行同自然键」
				SpikeCart dup = new SpikeCart();
				dup.setId("c3");
				dup.setTenantId("t1");
				dup.setUserId("u1");
				dup.setSkuId("sku1");
				dup.setQuantity(1);
				Exception e = assertThrows(Exception.class, () -> mapper.insert(dup));
				System.out.println("[Q3b] 两个活行同自然键仍被拦 → " + rootMessage(e));
			}
		}
		finally {
			try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
				// 先清表再恢复全量唯一索引：此刻表里正躺着「墓碑 + 活行」同自然键两行，
				// 不清就重建不出全量唯一索引（这本身也是本用例结论的一个侧证）
				s.execute("truncate table spike_cart");
				s.execute("drop index if exists uk_spike_cart_alive");
				s.execute("create unique index uk_spike_cart_full "
						+ "on spike_cart (tenant_id, user_id, sku_id)");
			}
		}
	}

	@Test
	@DisplayName("Q3c · 裸 SQL @Delete 不受 @TableLogic 影响（物理删逃生舱）")
	void rawDeleteBypassesLogicDelete() throws SQLException {
		insert("c1", "t1", "u1", "sku1", 2);

		try (SqlSession session = sqlSessionFactory.openSession(true)) {
			SpikeCartMapper mapper = session.getMapper(SpikeCartMapper.class);
			assertEquals(1, mapper.physicalRemoveById("c1"));
			assertEquals(0, rawCount("select count(*) from spike_cart"));
			System.out.println("\n[Q3c] @Delete 裸 SQL 真物理删，行数归零 ✓");
			assertFalse(firstSqlContaining("delete from spike_cart").isEmpty());
		}
	}

	// ─────────────────────────────── 基础设施 ───────────────────────────────

	private static void insert(String id, String tenant, String user, String sku, int qty)
			throws SQLException {
		try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
			s.execute("insert into spike_cart (id, tenant_id, user_id, sku_id, quantity, del_flag) "
					+ "values ('%s','%s','%s','%s',%d,'0')".formatted(id, tenant, user, sku, qty));
		}
	}

	private static String rawDelFlag(String id) throws SQLException {
		return rawString("select del_flag from spike_cart where id = '" + id + "'");
	}

	private static String rawUserId(String id) throws SQLException {
		return rawString("select user_id from spike_cart where id = '" + id + "'");
	}

	private static String rawString(String sql) throws SQLException {
		try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			return rs.next() ? rs.getString(1) : null;
		}
	}

	private static int rawCount(String sql) throws SQLException {
		try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			return rs.next() ? rs.getInt(1) : -1;
		}
	}

	private static String firstSqlContaining(String needle) {
		return RECORDER.sqls.stream()
				.filter(s -> s.toLowerCase().contains(needle.toLowerCase()))
				.findFirst()
				.orElse("");
	}

	private static String rootMessage(Throwable t) {
		Throwable cur = t;
		while (cur.getCause() != null) {
			cur = cur.getCause();
		}
		return cur.getMessage().split("\n")[0];
	}

	private static boolean dockerAvailable() {
		try {
			return DockerClientFactory.instance().isDockerAvailable();
		}
		catch (Throwable ignored) {
			return false;
		}
	}

	/** 抓 MyBatis 真正发出去的 SQL —— 断言要钉在实际语句上，不是猜的 */
	@Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
			args = { Connection.class, Integer.class }))
	static class SqlRecorder implements Interceptor {

		final List<String> sqls = new ArrayList<>();

		@Override
		public Object intercept(Invocation invocation) throws Throwable {
			StatementHandler handler = (StatementHandler) invocation.getTarget();
			sqls.add(handler.getBoundSql().getSql().replaceAll("\\s+", " ").trim());
			return invocation.proceed();
		}
	}

	/**
	 * 模拟 {@code common-core} 的 BaseEntity：delFlag 在基类，翻转后带 @TableLogic。
	 *
	 * <p><b>{@code updateTime} 不是装饰</b>：真实 {@code BaseEntity} 上它带
	 * {@code @TableField(fill = INSERT_UPDATE)}，配合全局 {@code ZhiflowMetaObjectHandler}
	 * 会让 MP 往每条 update 的 SET 里塞 {@code update_time}。少了它，spike 会得出
	 * 「SET 变空 → 500」这个在真应用里不成立的结论（见 Q1f）。
	 */
	public static class SpikeBaseEntity {

		@TableLogic(value = "0", delval = "1")
		private String delFlag;

		@TableField(fill = FieldFill.INSERT_UPDATE)
		private java.time.LocalDateTime updateTime;

		public String getDelFlag() {
			return delFlag;
		}

		public void setDelFlag(String delFlag) {
			this.delFlag = delFlag;
		}

		public java.time.LocalDateTime getUpdateTime() {
			return updateTime;
		}

		public void setUpdateTime(java.time.LocalDateTime updateTime) {
			this.updateTime = updateTime;
		}
	}

	/** 复刻 {@code ZhiflowMetaObjectHandler} 里与本实验相关的那一条填充 */
	static class SpikeMetaObjectHandler implements com.baomidou.mybatisplus.core.handlers.MetaObjectHandler {

		@Override
		public void insertFill(org.apache.ibatis.reflection.MetaObject metaObject) {
			strictInsertFill(metaObject, "updateTime", java.time.LocalDateTime.class,
					java.time.LocalDateTime.now());
		}

		@Override
		public void updateFill(org.apache.ibatis.reflection.MetaObject metaObject) {
			strictUpdateFill(metaObject, "updateTime", java.time.LocalDateTime.class,
					java.time.LocalDateTime.now());
		}
	}

	@TableName("spike_cart")
	public static class SpikeCart extends SpikeBaseEntity {

		@TableId(type = IdType.INPUT)
		private String id;

		private String tenantId;

		private String userId;

		private String skuId;

		private Integer quantity;

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getTenantId() {
			return tenantId;
		}

		public void setTenantId(String tenantId) {
			this.tenantId = tenantId;
		}

		public String getUserId() {
			return userId;
		}

		public void setUserId(String userId) {
			this.userId = userId;
		}

		public String getSkuId() {
			return skuId;
		}

		public void setSkuId(String skuId) {
			this.skuId = skuId;
		}

		public Integer getQuantity() {
			return quantity;
		}

		public void setQuantity(Integer quantity) {
			this.quantity = quantity;
		}
	}

	public interface SpikeCartMapper extends BaseMapper<SpikeCart> {

		/** 物理删逃生舱：裸 SQL 不经 MP 的逻辑删改写 */
		@org.apache.ibatis.annotations.Delete("delete from spike_cart where id = #{id}")
		int physicalRemoveById(@org.apache.ibatis.annotations.Param("id") String id);
	}
}
