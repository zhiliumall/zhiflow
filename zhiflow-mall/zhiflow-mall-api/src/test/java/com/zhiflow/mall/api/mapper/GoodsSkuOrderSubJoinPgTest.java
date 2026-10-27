package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.zhiflow.common.data.mybatis.SchemaInterceptor;
import com.zhiflow.common.data.shardingdatabase.config.ShardingDataBaseProperties;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.common.dto.PlaceOrderSkuDTO;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSpu;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 10-22 下单链路清理：{@code listSkuInOrderSub} 由「每个 SKU 嵌套 {@code GoodsSpuMapper.selectById}」改为一次 LEFT JOIN。
 * 真 PG 上逐字段对照：JOIN 取回的 {@code goodsSpu} 与原路径 {@code selectById} 读到的 SPU，除 description（下单不用、刻意不取）
 * 外每个持久化字段都相等；软删 SPU、别的租户同 id 的 SPU 都取不到（与 {@code @TableLogic} + 租户插件的原语义一致）。
 *
 * <p>表结构直接取 mall-admin 的基线迁移 {@code V1__baseline.sql}，不手写，避免和实际列类型漂移。
 * 默认整类跳过；与 {@code OrderStockDeductionPgTest} 同一开关、同样只接受本机回环地址：
 * {@code -Dhotsku.pg.url=jdbc:postgresql://127.0.0.1:5432/postgres}，密码取 {@code HOTSKU_PG_PASSWORD}。
 * 每次建唯一 schema，结束后删除。
 */
@EnabledIf("pgConfigured")
class GoodsSkuOrderSubJoinPgTest {

	private static final String DB_PREFIX = "sj_" + UUID.randomUUID().toString().replace("-", "");

	private static final String TENANT = "9";

	private static final String OTHER_TENANT = "x";

	private static final String SCHEMA = DB_PREFIX + "_" + TENANT;

	private static final Path BASELINE_DDL = Path.of(
			"../zhiflow-mall-admin/src/main/resources/db/migration/mall/V1__baseline.sql");

	private static final String MAPPER_XML = "mapper/mall-api/GoodsSkuMapper.xml";

	private static JdbcTemplate jdbc;

	private static GoodsSkuMapper skuMapper;

	private static GoodsSpuMapper spuMapper;

	private static boolean schemaCreated;

	static boolean pgConfigured() {
		String url = System.getProperty("hotsku.pg.url");
		return url != null && !url.isBlank();
	}

	@BeforeAll
	static void createFixture() throws Exception {
		String url = System.getProperty("hotsku.pg.url");
		assertTrue(url.startsWith("jdbc:postgresql://"), "仅支持显式指定的 PG JDBC 地址");
		URI endpoint = URI.create(url.substring("jdbc:".length()));
		assertTrue(List.of("127.0.0.1", "localhost", "[::1]", "::1").contains(endpoint.getHost()), "只允许本机回环地址");

		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setURL(url);
		dataSource.setUser(System.getProperty("hotsku.pg.user", "postgres"));
		String p = System.getProperty("hotsku.pg.password");
		dataSource.setPassword(p != null ? p : System.getenv().getOrDefault("HOTSKU_PG_PASSWORD", ""));
		dataSource.setApplicationName(DB_PREFIX);
		dataSource.setPrepareThreshold(1);
		jdbc = new JdbcTemplate(dataSource);
		try {
			jdbc.execute("CREATE SCHEMA " + SCHEMA);
			schemaCreated = true;
			String ddl = Files.readString(BASELINE_DDL, StandardCharsets.UTF_8);
			for (String table : List.of("goods_sku", "goods_spu")) {
				jdbc.execute(createTable(ddl, table)
						.replaceFirst("CREATE TABLE " + table + " ", "CREATE TABLE " + SCHEMA + "." + table + " "));
			}

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
					return !List.of("goods_sku", "goods_spu").contains(tableName);
				}
			});
			interceptors.addInnerInterceptor(tenantLine);

			MybatisConfiguration cfg = new MybatisConfiguration();
			cfg.setMapUnderscoreToCamelCase(true);
			cfg.setLogImpl(NoLoggingImpl.class);
			cfg.setEnvironment(new Environment("sku-join-pg", new SpringManagedTransactionFactory(), dataSource));
			cfg.addInterceptor(interceptors);
			// 与 mybatis-plus.type-handlers-package 配置一致：String[] ↔ VARCHAR 全局注册。
			// 没有它，原路径 selectById（@TableName 未开 autoResultMap）读不出数组列，对照就不成立。
			cfg.getTypeHandlerRegistry().register("com.zhiflow.common.core.mybatis.typehandler");
			cfg.addMapper(GoodsSpuMapper.class);
			try (InputStream in = GoodsSkuOrderSubJoinPgTest.class.getClassLoader().getResourceAsStream(MAPPER_XML)) {
				assertNotNull(in, MAPPER_XML);
				new XMLMapperBuilder(in, cfg, MAPPER_XML, cfg.getSqlFragments()).parse();
			}
			SqlSessionTemplate session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg));
			skuMapper = session.getMapper(GoodsSkuMapper.class);
			spuMapper = session.getMapper(GoodsSpuMapper.class);
		}
		catch (Exception | AssertionError e) {
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
		jdbc.execute("TRUNCATE " + SCHEMA + ".goods_sku, " + SCHEMA + ".goods_spu");
		// spu-full：每个映射列都填非默认值（数组列用两种存法），用来逐字段比对
		insertSpu("spu-full", TENANT, "0");
		jdbc.update("UPDATE " + SCHEMA + ".goods_spu SET category_second='c2', category_third='c3', video_url='v.mp4',"
				+ " pic_urls='[\"a.jpg\",\"b.jpg\"]', sort=7, price_down=1.10, price_up=9.90, sale_num=12,"
				+ " spec_type='1', freight_amount=6.00, brand_id='b1', label='x,y', parameter_json='{\"k\":1}',"
				+ " platform='[\"H5\",\"APP\"]', view_num=99, stock_warn_line=3 WHERE id='spu-full'");
		insertSpu("spu-deleted", TENANT, "1");
		// 别的租户、同 id 的 SPU：租户插件要把 JOIN 也限定在本租户
		insertSpu("spu-other", OTHER_TENANT, "0");

		insertSku("sku-full", "spu-full", 10);
		insertSku("sku-deleted-spu", "spu-deleted", 10);
		insertSku("sku-other-spu", "spu-other", 10);
		insertSku("sku-low", "spu-full", 1);
		TenantContextHolder.setTenantId(TENANT);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	@Test
	@DisplayName("JOIN 取回的 goodsSpu 与原路径 GoodsSpuMapper.selectById 逐字段相等（只差刻意不取的 description）")
	void joinedSpuEqualsSelectByIdExceptDescription() throws Exception {
		List<GoodsSku> rows = skuMapper.listSkuInOrderSub("shop1", List.of(item("sku-full", "spu-full", 2)));

		assertEquals(1, rows.size());
		GoodsSku sku = rows.getFirst();
		assertEquals("spu-full", sku.getSpuId());
		GoodsSpu joined = sku.getGoodsSpu();
		assertNotNull(joined);
		GoodsSpu legacy = spuMapper.selectById("spu-full");
		assertNotNull(legacy);
		assertNotNull(legacy.getDescription(), "对照：原路径会把 description 读出来");
		assertNull(joined.getDescription(), "下单路径不取 description");

		List<String> compared = new ArrayList<>();
		for (Field f : GoodsSkuOrderSubMapperTest.persistentFields(GoodsSpu.class)) {
			if ("description".equals(f.getName())) {
				continue;
			}
			f.setAccessible(true);
			Object expected = f.get(legacy);
			Object actual = f.get(joined);
			boolean same = expected instanceof Object[] e && actual instanceof Object[] a
					? Arrays.equals(e, a) : Objects.equals(expected, actual);
			assertTrue(same, "字段 " + f.getName() + " 不一致：selectById=" + str(expected) + " JOIN=" + str(actual));
			if (expected != null) {
				compared.add(f.getName());
			}
		}
		// 反向校准：真比了一批非空字段（防止两边都是 null 也算相等）
		assertEquals(29, compared.size(), "非空且相等的字段数不对：" + compared);
		assertEquals(List.of("a.jpg", "b.jpg"), Arrays.asList(joined.getPicUrls()));
		assertEquals(List.of("x", "y"), Arrays.asList(joined.getLabel()));
		assertEquals(List.of("H5", "APP"), Arrays.asList(joined.getPlatform()));
		assertEquals("9", joined.getTenantId());
	}

	@Test
	@DisplayName("软删的 SPU、别的租户同 id 的 SPU：goodsSpu 为 null（与 selectById 的 @TableLogic / 租户条件一致），调用方照旧拒单")
	void deletedOrForeignSpuIsNull() {
		Map<String, GoodsSku> rows = skuMapper.listSkuInOrderSub("shop1", List.of(
						item("sku-full", "spu-full", 1), item("sku-deleted-spu", "spu-deleted", 1),
						item("sku-other-spu", "spu-other", 1)))
				.stream().collect(Collectors.toMap(GoodsSku::getId, Function.identity()));

		assertEquals(3, rows.size(), "SKU 行本身都在，SPU 是否存在由 goodsSpu 是否为 null 表达");
		assertNotNull(rows.get("sku-full").getGoodsSpu());
		assertNull(rows.get("sku-deleted-spu").getGoodsSpu());
		assertNull(spuMapper.selectById("spu-deleted"), "对照：原路径同样读不到软删 SPU");
		assertNull(rows.get("sku-other-spu").getGoodsSpu());
		assertNull(spuMapper.selectById("spu-other"), "对照：原路径同样读不到别的租户的 SPU");
	}

	@Test
	@DisplayName("其它 WHERE 条件不变：库存不足的 SKU 不返回；多 SKU 各自一行、各带自己的 SPU")
	void otherPredicatesUnchanged() {
		List<GoodsSku> rows = skuMapper.listSkuInOrderSub("shop1", List.of(
				item("sku-full", "spu-full", 3), item("sku-low", "spu-full", 2)));

		assertEquals(List.of("sku-full"), rows.stream().map(GoodsSku::getId).toList());
		assertEquals("spu-full", rows.getFirst().getGoodsSpu().getId());
	}

	// ─────────────────────────── 工具 ───────────────────────────

	private static String createTable(String ddl, String table) {
		Matcher m = Pattern.compile("(?ms)^CREATE TABLE " + table + " \\(.*?^\\);").matcher(ddl);
		assertTrue(m.find(), "基线迁移里找不到 " + table + " 的建表语句");
		return m.group();
	}

	private static void insertSpu(String id, String tenant, String delFlag) {
		jdbc.update("INSERT INTO " + SCHEMA + ".goods_spu (id, tenant_id, shop_id, spu_code, name, sell_point, description,"
				+ " category_first, shelf, verify_status, freight_type, del_flag)"
				+ " VALUES (?, ?, 'shop1', ?, ?, 'sp', '<p>很长的详情</p>', 'c1', '1', '1', '0', ?)",
				id, tenant, "code-" + id, "N-" + id, delFlag);
	}

	private static void insertSku(String id, String spuId, int stock) {
		jdbc.update("INSERT INTO " + SCHEMA + ".goods_sku (id, tenant_id, shop_id, spu_id, sales_price, stock, enable,"
				+ " del_flag) VALUES (?, ?, 'shop1', ?, 9.90, ?, '1', '0')", id, TENANT, spuId, stock);
	}

	private static PlaceOrderSkuDTO item(String skuId, String spuId, int quantity) {
		PlaceOrderSkuDTO s = new PlaceOrderSkuDTO();
		s.setSkuId(skuId);
		s.setSpuId(spuId);
		s.setQuantity(quantity);
		return s;
	}

	private static String str(Object o) {
		return o instanceof Object[] a ? Arrays.toString(a) : String.valueOf(o);
	}
}
