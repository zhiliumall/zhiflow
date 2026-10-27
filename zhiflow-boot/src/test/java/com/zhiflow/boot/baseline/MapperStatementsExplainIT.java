/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.boot.baseline;

import com.zhiflow.boot.ZhiflowBootApplication;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基线库对账：全部 MyBatis 语句（mapper XML、注解 SQL、MyBatis-Plus 按实体生成的 CRUD）在真实的基线库上逐条 {@code EXPLAIN}。
 * 缺表、缺列、类型对不上当场报出来——这类错编译和装配冒烟都看不见，只在走到那条 SQL 时才炸。
 *
 * <p>参数一律按 null 渲染动态 SQL（走默认分支），{@code ?} 换成 {@code NULL} 再 EXPLAIN，不真执行。
 * 渲染本身失败（OGNL 访问 null 参数的属性）的语句单独列出，不算缺表缺列。
 *
 * <p>另一条用例按映射方法的真实参数类型构造参数对象再渲染：动态 SQL 引用了参数类型上不存在的属性
 * （实体删了字段、XML 没跟着删）也当场报出来——按 null 渲染时这类分支走不到，EXPLAIN 看不见。
 *
 * <p>第三条用例静态检查全部 resultMap：映射到的属性在目标类型上必须可写（实体删了字段、resultMap 没跟着删，
 * 要到真查出数据处理结果时才炸，前两条都看不见）。
 *
 * <p>只在给了 {@code -Dbaseline.pg.url=jdbc:postgresql://...} 时运行（需要一个装好基线的空库和本机 Redis）。
 */
@EnabledIfSystemProperty(named = "baseline.pg.url", matches = ".+")
@SpringBootTest(classes = ZhiflowBootApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MapperStatementsExplainIT {

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		// 与 application.yml 一样带上 currentSchema：没有租户上下文的语句（upms / wx 表）靠它解析
		r.add("spring.datasource.url", () -> {
			String url = System.getProperty("baseline.pg.url");
			return url.contains("currentSchema") ? url
					: url + (url.contains("?") ? "&" : "?") + "currentSchema=zhiflow_mall_1,zhiflow_upms,zhiflow_wx,public";
		});
		r.add("spring.datasource.username", () -> System.getProperty("baseline.pg.user", "postgres"));
		r.add("spring.datasource.password", () -> System.getProperty("baseline.pg.password", ""));
		r.add("mall.dev.scheduled-tasks-enabled", () -> "false");
	}

	/** MyBatis-Plus 按实体注入的通用方法：空参数下渲染成残句（SET WHERE 等），它们的列由同一实体的 selectById 覆盖。 */
	private static final java.util.Set<String> MP_GENERIC = java.util.Set.of("insert", "update", "updateById", "delete", "deleteById",
			"deleteByIds", "deleteBatchIds", "deleteByMap", "selectBatchIds", "selectByIds", "selectByMap", "selectList", "selectOne",
			"selectCount", "selectMaps", "selectObjs", "selectPage", "selectMapsPage", "exists");

	@Autowired
	private SqlSessionFactory sqlSessionFactory;

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("全部 MyBatis 语句在基线库上 EXPLAIN 通过（没有缺表 / 缺列）")
	void everyStatementExplains() throws Exception {
		List<String> broken = new ArrayList<>();
		TreeSet<String> renderSkipped = new TreeSet<>();
		// EXPLAIN 失败但不是缺表 / 缺列（NULL 参数导致的类型推断、渲染出的语法残缺等）：列出来供人看，不判失败
		List<String> other = new ArrayList<>();
		int explained = 0;
		try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
			c.setAutoCommit(false);
			st.execute("SET search_path TO zhiflow_mall_1, zhiflow_upms, zhiflow_wx, public");
			// 语句表按全名、短名各存一份，短名重复时存的是歧义占位对象：按类型过滤、按全名去重
			Map<String, MappedStatement> statements = new java.util.TreeMap<>();
			for (Object o : new ArrayList<Object>(sqlSessionFactory.getConfiguration().getMappedStatements())) {
				if (o instanceof MappedStatement ms) {
					statements.putIfAbsent(ms.getId(), ms);
				}
			}
			for (MappedStatement ms : statements.values()) {
				if (ms.getSqlCommandType() == SqlCommandType.UNKNOWN || ms.getSqlCommandType() == SqlCommandType.FLUSH
						|| ms.getId().endsWith("!selectKey")) {
					continue;
				}
				String sql;
				try {
					sql = render(ms, new HashMap<String, Object>(Map.of()));
				}
				catch (RuntimeException e) {
					// 第二轮：取任何键都给一个空对象，让 <if test="query.x != null"> 这类依赖参数对象的分支也渲染得出来
					try {
						sql = render(ms, new AnyKeyMap());
					}
					catch (RuntimeException e2) {
						renderSkipped.add(ms.getId());
						continue;
					}
				}
				if (sql.isBlank() || degenerate(sql)) {
					continue;
				}
				st.execute("SAVEPOINT s");
				try {
					st.execute("EXPLAIN " + sql);
					explained++;
				}
				catch (Exception e) {
					String msg = e.getMessage() == null ? e.toString() : e.getMessage().split("\n")[0];
					// 只报结构类错误：缺表 42P01、缺列 42703、类型 / 函数对不上 42883、42804；NULL 参数造成的歧义不算
					if (msg.contains("does not exist") || msg.contains("不存在")) {
						broken.add(ms.getId() + " → " + msg);
					}
					else if (!MP_GENERIC.contains(ms.getId().substring(ms.getId().lastIndexOf('.') + 1))) {
						other.add(ms.getId() + " → " + msg + "\n      SQL: " + sql.replaceAll("\\s+", " "));
					}
				}
				finally {
					st.execute("ROLLBACK TO SAVEPOINT s");
				}
			}
			c.rollback();
		}
		renderSkipped.forEach(id -> System.out.println("[baseline-explain] 渲染失败跳过 " + id));
		other.forEach(s -> System.out.println("[baseline-explain] 其它错误 " + s));
		System.out.printf("[baseline-explain] EXPLAIN 通过 %d 条；参数渲染失败跳过 %d 条；其它错误 %d 条%n", explained,
				renderSkipped.size(), other.size());
		assertTrue(explained > 300, "判据自检：EXPLAIN 通过的语句太少（" + explained + "），多半是连错库或 search_path 不对");
		assertTrue(broken.isEmpty(), () -> "基线库缺表 / 缺列：\n" + String.join("\n", broken));
	}

	@Test
	@DisplayName("动态 SQL 引用的参数属性在参数类型上都存在")
	void dynamicSqlPropertiesExistOnParamTypes() {
		List<String> broken = new ArrayList<>();
		int rendered = 0;
		Map<String, MappedStatement> statements = new java.util.TreeMap<>();
		for (Object o : new ArrayList<Object>(sqlSessionFactory.getConfiguration().getMappedStatements())) {
			if (o instanceof MappedStatement ms) {
				statements.putIfAbsent(ms.getId(), ms);
			}
		}
		for (MappedStatement ms : statements.values()) {
			String id = ms.getId();
			int dot = id.lastIndexOf('.');
			Class<?> mapper;
			try {
				mapper = Class.forName(id.substring(0, dot));
			}
			catch (ClassNotFoundException | LinkageError e) {
				continue;
			}
			for (Method m : mapper.getMethods()) {
				if (!m.getName().equals(id.substring(dot + 1)) || m.isDefault() || m.isBridge()) {
					continue;
				}
				Object[] args = Arrays.stream(m.getParameterTypes()).map(MapperStatementsExplainIT::sample).toArray();
				Object param = new ParamNameResolver(sqlSessionFactory.getConfiguration(), m).getNamedParams(args);
				try {
					ms.getBoundSql(param);
					rendered++;
				}
				catch (RuntimeException e) {
					String miss = missingProperty(e);
					if (miss != null) {
						broken.add(id + " → " + miss);
					}
				}
			}
		}
		System.out.printf("[baseline-explain] 按参数类型渲染 %d 条；引用了不存在的属性 %d 条%n", rendered, broken.size());
		assertTrue(rendered > 300, "判据自检：按参数类型渲染成功的语句太少（" + rendered + "）");
		assertTrue(broken.isEmpty(), () -> "动态 SQL 引用了参数类型上不存在的属性：\n" + String.join("\n", broken));
	}

	@Test
	@DisplayName("resultMap 映射到的属性在目标类型上都可写")
	void resultMapPropertiesExist() {
		org.apache.ibatis.session.Configuration cfg = sqlSessionFactory.getConfiguration();
		List<String> broken = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		int checked = 0;
		for (Object o : new ArrayList<Object>(cfg.getResultMaps())) {
			if (!(o instanceof ResultMap rm) || !seen.add(rm.getId()) || Map.class.isAssignableFrom(rm.getType())) {
				continue;
			}
			MetaClass meta = MetaClass.forClass(rm.getType(), cfg.getReflectorFactory());
			for (ResultMapping m : rm.getResultMappings()) {
				if (m.getProperty() == null || m.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
					continue;
				}
				checked++;
				if (!meta.hasSetter(m.getProperty())) {
					broken.add(rm.getId() + " → " + rm.getType().getSimpleName() + "." + m.getProperty());
				}
			}
		}
		System.out.printf("[baseline-explain] resultMap 属性检查 %d 个；目标类型上不可写 %d 个%n", checked, broken.size());
		assertTrue(checked > 100, "判据自检：检查到的 resultMap 属性太少（" + checked + "）");
		assertTrue(broken.isEmpty(), () -> "resultMap 映射到了目标类型上不存在（或不可写）的属性：\n" + String.join("\n", broken));
	}

	/** 参数样本：集合给空集合、分页给第一页、普通 POJO 用无参构造，其余（基本包装类、字符串、接口）给 null。 */
	private static Object sample(Class<?> t) {
		if (t.isPrimitive()) {
			return t == boolean.class ? false : t == char.class ? 'a' : java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(t, 1), 0);
		}
		if (t.isArray()) {
			return java.lang.reflect.Array.newInstance(t.getComponentType(), 0);
		}
		if (Collection.class.isAssignableFrom(t)) {
			return java.util.Set.class.isAssignableFrom(t) ? new java.util.HashSet<>() : new ArrayList<>();
		}
		if (Map.class.isAssignableFrom(t)) {
			return new HashMap<>();
		}
		if (com.baomidou.mybatisplus.core.metadata.IPage.class.isAssignableFrom(t)) {
			return new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
		}
		if (t.isInterface() || t.isEnum() || java.lang.reflect.Modifier.isAbstract(t.getModifiers()) || t.getName().startsWith("java.")
				|| com.baomidou.mybatisplus.core.conditions.Wrapper.class.isAssignableFrom(t)) {
			return null;
		}
		try {
			return t.getDeclaredConstructor().newInstance();
		}
		catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** 异常链里是「属性不存在」（OGNL NoSuchProperty / MyBatis 没有 getter）时返回说明，否则 null。 */
	private static String missingProperty(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			String msg = String.valueOf(t.getMessage());
			if (t.getClass().getSimpleName().equals("NoSuchPropertyException") || msg.contains("There is no getter for property")) {
				return msg.split("\n")[0];
			}
		}
		return null;
	}

	private static String render(MappedStatement ms, Object param) {
		BoundSql b = ms.getBoundSql(param);
		// 空集合的 foreach 渲染成 IN ()；空参数对象 ${} 渲染成 {}（AnyKeyMap.toString）
		// 空集合的 foreach 渲染成 IN ()，或连括号都没有（open / close 随空集合一起省掉）：都补成 IN (NULL)
		String sql = b.getSql().replace("?", "NULL").replace("{}", "");
		sql = sql.replaceAll("(?i)\\bIN\\s*\\(\\s*\\)", "IN (NULL)");
		return sql.replaceAll("(?i)\\bIN\\s*(?=$|\\)|\\bAND\\b|\\bOR\\b|\\bORDER\\b|\\bGROUP\\b|\\bTHEN\\b|\\bLIMIT\\b)", "IN (NULL) ");
	}

	/**
	 * MyBatis-Plus 通用方法在空实体 / 空条件下渲染出的残句（{@code UPDATE t SET WHERE}、{@code INSERT INTO t () VALUES ()}）：
	 * 这些方法的列来自实体，已由 selectById / updateById 等同一实体的其它通用语句覆盖，残句本身没有可查的结构。
	 */
	private static boolean degenerate(String sql) {
		String s = sql.replaceAll("\\s+", " ").toUpperCase();
		return s.startsWith("SET ") || s.contains(" SET WHERE") || s.endsWith(" SET") || s.contains("() VALUES ()") || s.contains(" SET  WHERE");
	}

	/** 取任何键都返回一个新的 {@code AnyKeyMap}（不是 null）：动态 SQL 里 {@code a.b.c} 这样的取值都能渲染。 */
	private static final class AnyKeyMap extends HashMap<String, Object> {

		@Override
		public Object get(Object key) {
			return super.computeIfAbsent(String.valueOf(key), k -> new AnyKeyMap());
		}

		@Override
		public boolean containsKey(Object key) {
			return true;
		}
	}
}
