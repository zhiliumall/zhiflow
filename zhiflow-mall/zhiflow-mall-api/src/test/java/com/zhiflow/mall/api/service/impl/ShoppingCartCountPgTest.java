/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zhiflow.common.data.cache.RedisTemplateConfig;
import com.zhiflow.mall.api.cache.ShoppingCartCache;
import com.zhiflow.mall.api.cache.ShoppingCartSnapshot;
import com.zhiflow.mall.api.mapper.ShoppingCartMapper;
import com.zhiflow.mall.common.entity.ShoppingCart;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 购物车件数（一条 COUNT）与购物车列表（三表 LEFT JOIN + 内存过滤）在真实 PostgreSQL 上数的是同一批行。
 *
 * <p>造的数据覆盖列表会遇到的每种情况：正常、下架、商品已删、商品行不存在、规格已删、店铺停用、
 * 购物车行已删、别人的购物车、另一个店铺。跑的是 mapper XML 里的原 SQL。
 *
 * <p><b>默认跳过，只许指向一次性库</b>（会建 / 删自己的 schema）：
 * {@code -Dcart.pg.url=jdbc:postgresql://127.0.0.1:55433/zhiflow -Dcart.pg.user=postgres -Dcart.pg.password=...}
 */
@EnabledIfSystemProperty(named = "cart.pg.url", matches = ".+")
class ShoppingCartCountPgTest {

	private static final String SCHEMA = "cart_count_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

	private static SqlSessionFactory factory;

	@BeforeAll
	static void createSchema() throws Exception {
		try (Connection c = dataSource(null).getConnection(); Statement st = c.createStatement()) {
			st.execute("CREATE SCHEMA " + SCHEMA);
			// 列与 mall 租户 schema 一致（只建这条 SQL 用到的列）
			st.execute("CREATE TABLE " + SCHEMA + ".shopping_cart (id varchar(32) PRIMARY KEY, tenant_id varchar(36) DEFAULT '1',"
					+ " shop_id varchar(32) NOT NULL, del_flag varchar(2) DEFAULT '0' NOT NULL,"
					+ " create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,"
					+ " user_id varchar(32) NOT NULL, spu_id varchar(32) NOT NULL, sku_id varchar(32) NOT NULL, quantity integer NOT NULL,"
					+ " spu_name varchar(200), add_price numeric(10,2), spec_info varchar(200), pic_url varchar(500), page_type varchar(255))");
			st.execute("CREATE TABLE " + SCHEMA + ".goods_spu (id varchar(32) PRIMARY KEY, tenant_id varchar(36), shop_id varchar(32),"
					+ " spu_code varchar(64), name varchar(200), sell_point varchar(500), description text, category_first varchar(32),"
					+ " category_second varchar(32), category_third varchar(32), video_url varchar(500), pic_urls varchar(2000),"
					+ " shelf varchar(2), sort integer, price_down numeric(10,2), price_up numeric(10,2), sale_num integer,"
					+ " create_time timestamp, update_time timestamp, spec_type varchar(2), del_flag varchar(2), freight_type varchar(2),"
					+ " freight_amount numeric(10,2), verify_status varchar(2), brand_id varchar(32), label varchar(500),"
					+ " parameter_json text, platform varchar(200), view_num integer)");
			st.execute("CREATE TABLE " + SCHEMA + ".goods_sku (id varchar(32) PRIMARY KEY, create_time timestamp, update_time timestamp,"
					+ " tenant_id varchar(36), shop_id varchar(32), sku_code varchar(64), spu_id varchar(32), pic_url varchar(500),"
					+ " sales_price numeric(10,2), market_price numeric(10,2), cost_price numeric(10,2), stock integer,"
					+ " weight numeric(10,2), volume numeric(10,2), enable varchar(2), del_flag varchar(2), version integer)");
			st.execute("CREATE TABLE " + SCHEMA + ".shop_info (id varchar(32) PRIMARY KEY, tenant_id varchar(36), del_flag varchar(2),"
					+ " create_time timestamp, update_time timestamp, enable varchar(2), phone varchar(32), name varchar(64), img_url varchar(500))");

			st.execute("INSERT INTO " + SCHEMA + ".shop_info (id, del_flag, enable, name) VALUES ('s1', '0', '1', '店1'), ('s2', '0', '0', '停用店')");
			st.execute("INSERT INTO " + SCHEMA + ".goods_spu (id, name, shelf, del_flag, description, parameter_json) VALUES"
					+ " ('p-ok', '正常', '1', '0', '<p>大字段</p>', '{}'), ('p-off', '下架', '0', '0', null, null),"
					+ " ('p-del', '已删', '1', '1', null, null)");
			st.execute("INSERT INTO " + SCHEMA + ".goods_sku (id, spu_id, sales_price, cost_price, stock, enable, del_flag) VALUES"
					+ " ('k-ok', 'p-ok', 10, 3, 5, '1', '0'), ('k-del', 'p-ok', 10, 3, 5, '1', '1'),"
					+ " ('k-off', 'p-off', 10, 3, 5, '1', '0'), ('k-pdel', 'p-del', 10, 3, 5, '1', '0')");
			String cols = " (id, shop_id, del_flag, user_id, spu_id, sku_id, quantity, spu_name) VALUES ";
			st.execute("INSERT INTO " + SCHEMA + ".shopping_cart" + cols
					+ "('c1', 's1', '0', 'u1', 'p-ok', 'k-ok', 1, '正常'),"
					+ "('c2', 's1', '0', 'u1', 'p-off', 'k-off', 1, '下架'),"
					+ "('c3', 's1', '0', 'u1', 'p-del', 'k-pdel', 1, '已删'),"
					+ "('c4', 's1', '0', 'u1', 'p-gone', 'k-gone', 1, null),"
					+ "('c5', 's1', '0', 'u1', 'p-ok', 'k-del', 1, '规格已删'),"
					+ "('c6', 's2', '0', 'u1', 'p-ok', 'k-ok', 2, '停用店'),"
					+ "('c7', 's1', '1', 'u1', 'p-ok', 'k-ok', 1, '购物车行已删'),"
					+ "('c8', 's1', '0', 'u2', 'p-ok', 'k-ok', 1, '别人的')");
			// 每一列都有值的一行（时间带微秒、金额两位小数、数组列），用来比「经缓存」与「直接查库」逐字段相同
			st.execute("INSERT INTO " + SCHEMA + ".goods_spu VALUES ('p-full', '1', 's1', 'SPU-F', '全字段商品', '卖点', '<p>d</p>',"
					+ " 'c1', 'c2', 'c3', 'https://v/1.mp4', 'https://img/a.png,https://img/b.png', '1', 3, 9.90, 19.90, 7,"
					+ " '2026-10-23 10:00:00.123456', '2026-10-23 11:00:00.654321', '1', '0', '1', 5.00, '1', 'b1',"
					+ " '[\"新品\",\"热卖\"]', '{}', 'h5,app', 42)");
			st.execute("INSERT INTO " + SCHEMA + ".goods_sku VALUES ('k-full', '2026-10-23 10:00:00.111111', '2026-10-23 11:00:00.222222',"
					+ " '1', 's1', 'SKU-F', 'p-full', 'https://img/sku.png', 9.90, 12.50, 3.30, 8, 0.25, 1.50, '1', '0', 4)");
			st.execute("UPDATE " + SCHEMA + ".shop_info SET tenant_id = '1', create_time = '2026-01-01 00:00:00.5',"
					+ " update_time = '2026-01-02 00:00:00.5', phone = '400', img_url = 'https://img/logo.png' WHERE id = 's1'");
			st.execute("INSERT INTO " + SCHEMA + ".shopping_cart VALUES ('c-full', '1', 's1', '0', '2026-10-23 12:00:00.333333',"
					+ " '2026-10-23 12:30:00.444444', 'u-full', 'p-full', 'k-full', 2, '加购时名字', 9.90, '红,大', 'https://img/cart.png', 'goods')");
			st.execute("INSERT INTO " + SCHEMA + ".shopping_cart (id, shop_id, del_flag, user_id, spu_id, sku_id, quantity, update_time)"
					+ " VALUES ('c-full2', 's2', '0', 'u-full', 'p-off', 'k-off', 1, '2026-10-23 09:00:00')");
		}
		MybatisConfiguration cfg = new MybatisConfiguration();
		cfg.setMapUnderscoreToCamelCase(true);
		cfg.setEnvironment(new Environment("cart-count-it", new JdbcTransactionFactory(), dataSource(SCHEMA)));
		// 先解析 XML（顺带绑定 mapper 接口与其上的注解语句）；反过来先 addMapper 会去同包路径找 XML，找不到
		String resource = "mapper/mall-api/ShoppingCartMapper.xml";
		try (InputStream in = ShoppingCartCountPgTest.class.getClassLoader().getResourceAsStream(resource)) {
			new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
		}
		factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
	}

	@AfterAll
	static void dropSchema() throws SQLException {
		try (Connection c = dataSource(null).getConnection(); Statement st = c.createStatement()) {
			st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
		}
	}

	@Test
	@DisplayName("件数 = 列表条数：只按用户、按用户 + 店铺、没有购物车的用户")
	void countMatchesList() {
		try (SqlSession session = factory.openSession(true)) {
			ShoppingCartServiceImpl service = service(session.getMapper(ShoppingCartMapper.class));
			for (ShoppingCart query : List.of(query("u1", null), query("u1", "s1"), query("u1", "s2"), query("u2", null),
					query("nobody", null))) {
				assertEquals(service.listCart(query).size(), service.countByCondition(query),
						"user=" + query.getUserId() + " shop=" + query.getShopId());
			}
			assertEquals(6, service.countByCondition(query("u1", null)), "下架、删除、不存在的商品都还在列表里（H5 归到失效商品）");
		}
	}

	@Test
	@DisplayName("列表不再带成本价、富文本、参数")
	void listHasNoCostPriceOrHeavyFields() {
		try (SqlSession session = factory.openSession(true)) {
			List<ShoppingCart> rows = service(session.getMapper(ShoppingCartMapper.class)).listCart(query("u1", null));
			ShoppingCart ok = rows.stream().filter(r -> "c1".equals(r.getId())).findFirst().orElseThrow();
			assertEquals("正常", ok.getGoodsSpu().getName());
			assertEquals(5, ok.getGoodsSku().getStock());
			assertNull(ok.getGoodsSku().getCostPrice());
			assertNull(ok.getGoodsSpu().getDescription());
			assertNull(ok.getGoodsSpu().getParameterJson());
			assertTrue(rows.stream().allMatch(r -> r.getGoodsSku() == null || r.getGoodsSku().getCostPrice() == null));
		}
	}

	@Test
	@DisplayName("整份购物车经缓存（生产序列化器往返）与直接查库逐字段相同：全字段行、下架 / 删除 / 不存在的商品、停用店铺")
	void wholeCartThroughCacheEqualsDatabase() {
		var serializer = RedisTemplateConfig.createValkeyOptimizedJsonSerializer();
		try (SqlSession session = factory.openSession(true)) {
			ShoppingCartMapper mapper = session.getMapper(ShoppingCartMapper.class);
			for (String user : List.of("u1", "u2", "u-full", "nobody")) {
				List<ShoppingCart> fromDb = ShoppingCartCache.load(mapper, query(user, null));
				ShoppingCartSnapshot cached = (ShoppingCartSnapshot) serializer.deserialize(
						serializer.serialize(ShoppingCartSnapshot.of(fromDb)));
				assertEquals(fromDb, cached.toCarts(), "user=" + user);
			}
			ShoppingCart full = ShoppingCartCache.load(mapper, query("u-full", null)).stream()
					.filter(r -> "c-full".equals(r.getId())).findFirst().orElseThrow();
			assertEquals("SPU-F", full.getGoodsSpu().getSpuCode(), "前提：全字段行确实带出了商品列");
			assertEquals(new java.math.BigDecimal("12.50"), full.getGoodsSku().getMarketPrice());
		}
	}

	private static ShoppingCartServiceImpl service(ShoppingCartMapper mapper) {
		// 整份购物车走 ShoppingCartCache（这里没有缓存切面：每次都查库，并经快照转换），带筛选的直接查库
		ShoppingCartServiceImpl service = new ShoppingCartServiceImpl(null, null,
				new ShoppingCartCache(mapper, null));
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		return service;
	}

	private static ShoppingCart query(String userId, String shopId) {
		ShoppingCart query = new ShoppingCart();
		query.setUserId(userId);
		query.setShopId(shopId);
		return query;
	}

	private static PGSimpleDataSource dataSource(String schema) {
		PGSimpleDataSource ds = new PGSimpleDataSource();
		ds.setURL(System.getProperty("cart.pg.url"));
		ds.setUser(System.getProperty("cart.pg.user", "postgres"));
		ds.setPassword(System.getProperty("cart.pg.password", ""));
		if (schema != null) {
			ds.setCurrentSchema(schema);
		}
		return ds;
	}
}
