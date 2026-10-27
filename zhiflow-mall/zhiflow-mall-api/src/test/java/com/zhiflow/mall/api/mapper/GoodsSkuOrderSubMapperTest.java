package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.zhiflow.common.core.mybatis.typehandler.ArrayStringTypeHandler;
import com.zhiflow.mall.common.dto.PlaceOrderSkuDTO;
import com.zhiflow.mall.common.entity.GoodsSpu;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 10-22 下单链路清理：{@code listSkuInOrderSub} 由「每个 SKU 嵌套 {@code GoodsSpuMapper.selectById}」改为一次 LEFT JOIN。
 * 不连库的结构护栏（真库逐字段对照见 {@link GoodsSkuOrderSubJoinPgTest}）：
 * 结果映射不再有嵌套 select（原来每个 SKU 多一次 goods_spu 查询）、映射了 GoodsSpu 除 description 外的全部物理列、
 * 生成的 SQL 带 JOIN 且不取 description。
 */
class GoodsSkuOrderSubMapperTest {

	private static final String NS = "com.zhiflow.mall.api.mapper.GoodsSkuMapper.";

	private static final String MAPPER_XML = "mapper/mall-api/GoodsSkuMapper.xml";

	private static MybatisConfiguration cfg;

	@BeforeAll
	static void parse() throws Exception {
		cfg = new MybatisConfiguration();
		cfg.setMapUnderscoreToCamelCase(true);
		try (InputStream in = GoodsSkuOrderSubMapperTest.class.getClassLoader().getResourceAsStream(MAPPER_XML)) {
			assertNotNull(in, MAPPER_XML);
			new XMLMapperBuilder(in, cfg, MAPPER_XML, cfg.getSqlFragments()).parse();
		}
	}

	@Test
	@DisplayName("listSkuInOrderSub 的结果映射没有嵌套 select（N+1 不回来）")
	void noNestedSelect() {
		MappedStatement ms = cfg.getMappedStatement(NS + "listSkuInOrderSub");
		ResultMap rm = ms.getResultMaps().getFirst();
		assertEquals(NS + "goodsSkuMapOrderSub", rm.getId());
		ResultMapping spu = rm.getResultMappings().stream()
				.filter(m -> "goodsSpu".equals(m.getProperty())).findFirst().orElseThrow();
		assertNull(spu.getNestedQueryId(), "嵌套 select 又回来了");
		assertNotNull(spu.getNestedResultMapId(), "goodsSpu 应由同一行的 JOIN 列装配");
		assertEquals("gspu_", spu.getColumnPrefix());
		assertEquals(Set.of("id"), spu.getNotNullColumns(), "notNullColumn 省了会给不存在的 SPU 造空对象");
		for (ResultMapping m : rm.getResultMappings()) {
			assertNull(m.getNestedQueryId(), "嵌套 select：" + m.getProperty());
		}
	}

	@Test
	@DisplayName("goodsSpu 映射 = GoodsSpu 全部物理列 − description；三个数组列走 ArrayStringTypeHandler")
	void spuMappingCoversAllPersistentColumnsButDescription() {
		ResultMap rm = cfg.getResultMap(NS + "goodsSkuMapOrderSub");
		String nestedId = rm.getResultMappings().stream()
				.filter(m -> "goodsSpu".equals(m.getProperty())).findFirst().orElseThrow().getNestedResultMapId();
		ResultMap spuMap = cfg.getResultMap(nestedId);
		Map<String, ResultMapping> byProperty = new HashMap<>();
		spuMap.getResultMappings().forEach(m -> byProperty.put(m.getProperty(), m));

		Set<String> expected = persistentFields(GoodsSpu.class).stream()
				.map(Field::getName).filter(n -> !"description".equals(n)).collect(Collectors.toCollection(TreeSet::new));
		assertEquals(expected, new TreeSet<>(byProperty.keySet()));
		for (String arr : List.of("picUrls", "label", "platform")) {
			assertTrue(byProperty.get(arr).getTypeHandler() instanceof ArrayStringTypeHandler, arr);
		}
	}

	@Test
	@DisplayName("生成的 SQL：每段 UNION ALL 都 LEFT JOIN goods_spu（ON 带 del_flag），不取 description，WHERE 条件不变")
	void sqlJoinsSpuWithoutDescription() {
		PlaceOrderSkuDTO a = item("sku-a", "spu-a", 1);
		PlaceOrderSkuDTO b = item("sku-b", "spu-b", 2);
		Map<String, Object> params = new HashMap<>();
		params.put("shopId", "shop1");
		params.put("listPlaceOrderSkuDTO", List.of(a, b));
		BoundSql sql = cfg.getMappedStatement(NS + "listSkuInOrderSub").getBoundSql(params);
		String text = sql.getSql().replaceAll("\\s+", " ");

		assertEquals(2, count(text, "LEFT JOIN goods_spu spu ON spu.id = sku.spu_id AND spu.del_flag = '0'"));
		assertEquals(1, count(text, "UNION ALL"));
		assertFalse(text.contains("description"), text);
		assertEquals(2, count(text, "AND sku.stock >= ?"));
		assertEquals(2, count(text, "spu.id AS gspu_id"));
	}

	private static int count(String s, String sub) {
		int n = 0;
		for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
			n++;
		}
		return n;
	}

	private static PlaceOrderSkuDTO item(String skuId, String spuId, int quantity) {
		PlaceOrderSkuDTO s = new PlaceOrderSkuDTO();
		s.setSkuId(skuId);
		s.setSpuId(spuId);
		s.setQuantity(quantity);
		return s;
	}

	/** 实体里映射到表的字段：非 static、非 {@code @TableField(exist = false)}，含父类 TenantEntity / BaseEntity。 */
	static List<Field> persistentFields(Class<?> type) {
		List<Field> out = new java.util.ArrayList<>();
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers())) {
					continue;
				}
				var tf = f.getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class);
				if (tf != null && !tf.exist()) {
					continue;
				}
				out.add(f);
			}
		}
		return out;
	}
}
