package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlLegacyScanner} 的规则自测：每条规则一个违规样例（必须命中）+ 合规样例（必须不命中），
 * 外加判据最容易坏的两处对照——注释里提到、SQL 字符串里出现。
 *
 * <p>门禁本身（{@link SqlLegacyScanGateTest}）在真实仓库上跑，存量全在基线里，所以它的「绿」证明不了
 * 规则还能抓到东西；这里用临时目录造一个最小仓库，证明「新增一条违规 → 比对结果非空」。
 */
class SqlLegacyScannerTest {

	private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
			+ "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" "
			+ "\"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n";

	private static final String BAD_XML = HEAD + """
			<mapper namespace="demo.BadMapper">
			  <select id="ifnull">SELECT IFNULL(a, 0) FROM t</select>
			  <select id="dateFmt">SELECT DATE_FORMAT(create_time, '%Y') FROM t</select>
			  <select id="interval">SELECT * FROM t WHERE create_time &gt; now() - INTERVAL #{days} DAY</select>
			  <select id="concat">SELECT GROUP_CONCAT(name) FROM t</select>
			  <select id="iff">SELECT IF(a &gt; 0, 1, 0) FROM t</select>
			  <select id="tick">SELECT `name` FROM t</select>
			  <select id="limit">SELECT * FROM t LIMIT #{offset}, #{size}</select>
			  <insert id="upsert">INSERT INTO t(a) VALUES (1) ON DUPLICATE KEY UPDATE a = 1</insert>
			  <select id="regexp">SELECT * FROM t WHERE name REGEXP '^a'</select>
			  <select id="cast">SELECT CAST(a AS SIGNED) FROM t</select>
			  <select id="field">SELECT * FROM t ORDER BY FIELD(id, 3, 1, 2)</select>
			  <select id="schema">SELECT * FROM zhiflow_mall_1.order_info</select>
			  <select id="dq">SELECT * FROM t WHERE status = "1"</select>
			  <select id="findOne">SELECT * FROM t
			    <where>
			      <if test="a != null">AND a = #{a}</if>
			      <if test="b != null">AND b = #{b}</if>
			    </where>
			  </select>
			  <select id="findOneWhere11">SELECT * FROM t WHERE 1=1 <if test="a != null">AND a = #{a}</if> ORDER BY id DESC</select>
			  <select id="findOneSubLimit">SELECT * FROM t WHERE 1=1 AND <if test="a != null"> a IN (SELECT a FROM x LIMIT 5)</if></select>
			</mapper>
			""";

	private static final String BAD_MAPPER_JAVA = """
			package demo;
			public interface BadMapper {
			    Foo findOne(@Param("a") String a, @Param("b") String b);
			    Map<String, Object> findOneWhere11(@Param("a") String a);
			    Foo findOneSubLimit(@Param("a") String a);
			}
			""";

	private static final String GOOD_XML = HEAD + """
			<mapper namespace="demo.GoodMapper">
			  <!-- 历史：原来写 IFNULL(a,0) 与 `name`、LIMIT 1, 2，已改 -->
			  <select id="coalesce">SELECT COALESCE(a, 0) FROM t -- 原 IFNULL(a, 0)
			  </select>
			  <select id="pageLimit">SELECT * FROM t LIMIT #{size} OFFSET #{offset}</select>
			  <select id="agg">SELECT string_agg(name, ',') FROM t WHERE note = 'IFNULL(x) `y` LIMIT 1, 2'</select>
			  <select id="interval">SELECT * FROM t WHERE create_time &gt; now() - INTERVAL '1 day'</select>
			  <insert id="upsert">INSERT INTO t(a) VALUES (1) ON CONFLICT (a) DO NOTHING</insert>
			  <select id="regex">SELECT regexp_replace(name, 'a', 'b') FROM t WHERE name ~ '^a'</select>
			  <select id="shared">SELECT * FROM zhiflow_upms.table_area</select>
			  <select id="quotedIdent">SELECT o.id FROM t o JOIN x ON x.id = "order".user_id</select>
			  <select id="findOneHard">SELECT * FROM t <where> tenant_id = #{t} <if test="a != null">AND a = #{a}</if></where></select>
			  <select id="findOneLimit">SELECT * FROM t <where><if test="a != null">AND a = #{a}</if></where> ORDER BY id LIMIT 1</select>
			  <select id="listAll">SELECT * FROM t <where><if test="a != null">AND a = #{a}</if></where></select>
			  <select id="countAll">SELECT COUNT(*) FROM t <where><if test="a != null">AND a = #{a}</if></where></select>
			  <select id="chooseOtherwise">SELECT * FROM t <where><choose><when test="a != null">a = #{a}</when><otherwise>id = #{id}</otherwise></choose></where></select>
			  <select id="byId">SELECT * FROM t WHERE id = #{id} <if test="a != null">AND a = #{a}</if></select>
			  <select id="countSubGroup">SELECT COUNT(*) FROM (SELECT user_id FROM f GROUP BY user_id) t WHERE 1 = 1 <if test="a != null">AND t.a = #{a}</if></select>
			</mapper>
			""";

	private static final String GOOD_MAPPER_JAVA = """
			package demo;
			public interface GoodMapper {
			    Foo findOneHard(@Param("t") String t, @Param("a") String a);
			    Foo findOneLimit(@Param("a") String a);
			    List<Foo> listAll(@Param("a") String a);
			    Integer countAll(@Param("a") String a);
			    Foo chooseOtherwise(@Param("a") String a, @Param("id") Long id);
			    Foo byId(@Param("id") Long id, @Param("a") String a);
			    Long countSubGroup(@Param("a") String a);
			}
			""";

	private static final String BAD_JAVA = """
			package demo;
			public interface BadAnnoMapper {
			    @Select("SELECT IFNULL(a, 0) FROM t WHERE id = #{id}")
			    Integer a(Long id);

			    @Select({"<script>", "SELECT * FROM t WHERE a &lt; 3 LIMIT #{o}, #{s}", "</script>"})
			    List<Foo> b(int o, int s);
			}
			class Svc {
			    void x(Foo entity, int y) {
			        mapper.selectList(Wrappers.<Foo>lambdaQuery().apply("DATE_FORMAT(create_time,'%Y') = {0}", y));
			        mapper.selectList(Wrappers.query(entity));
			        mapper.selectList(new LambdaQueryWrapper<Foo>(entity));
			    }
			}
			""";

	private static final String GOOD_JAVA = """
			package demo;
			public interface GoodAnnoMapper {
			    // 原写法 @Select("SELECT IFNULL(a,0) FROM t") 已改
			    /* 另一处 .apply("DATE_FORMAT(x)") 也已改 */
			    @Select("SELECT COALESCE(a, 0) FROM t WHERE id = #{id} AND note <> 'IFNULL('")
			    Integer a(Long id);
			}
			class Svc2 {
			    void x(Class<Foo> entityClass) {
			        Wrappers.lambdaQuery(Foo.class);
			        Wrappers.<Foo>lambdaQuery();
			        Wrappers.lambdaQuery(entityClass);
			        new QueryWrapper<Foo>();
			        log.info("IFNULL( 只是一句日志");
			    }
			}
			""";

	/** 只连 PG 的 JdbcTemplate 文件：跨 + 拼接的 LIMIT a,b 也要抓到。 */
	private static final String PG_JDBC_JAVA = """
			package demo;
			class Recon {
			    JdbcTemplate jdbc;
			    void r() {
			        jdbc.queryForList("SELECT IFNULL(a, 0) FROM t "
			                + "ORDER BY id LIMIT 1, "
			                + "2");
			        log.warn("IFNULL( 不是 SQL，只是提示文字");
			    }
			}
			""";

	private static Map<String, String> sources() {
		return Map.of("demo.BadMapper", BAD_MAPPER_JAVA, "demo.GoodMapper", GOOD_MAPPER_JAVA);
	}

	private static Set<String> rulesOf(List<SqlLegacyScanner.Finding> fs) {
		return fs.stream().map(SqlLegacyScanner.Finding::rule).collect(Collectors.toCollection(TreeSet::new));
	}

	private static Set<String> keysOf(List<SqlLegacyScanner.Finding> fs) {
		return fs.stream()
				.map(f -> f.anchor() + ":" + f.rule())
				.collect(Collectors.toCollection(TreeSet::new));
	}

	@Test
	@DisplayName("mapper XML：每条方言规则与 selectOne 全可空各有一个违规样例被抓到")
	void badXmlIsCaught() {
		List<SqlLegacyScanner.Finding> fs = SqlLegacyScanner.scanMapperXml("x/BadMapper.xml", BAD_XML,
				sources()::get);
		assertEquals(Set.of("ifnull:MYSQL_IFNULL", "dateFmt:MYSQL_DATE_FUNC", "interval:MYSQL_INTERVAL",
				"concat:MYSQL_FUNC", "iff:MYSQL_IF_FUNC", "tick:MYSQL_BACKTICK", "limit:MYSQL_LIMIT_COMMA",
				"upsert:MYSQL_UPSERT", "regexp:MYSQL_REGEXP", "cast:MYSQL_CAST", "field:MYSQL_ORDER_FIELD",
				"schema:TENANT_SCHEMA_HARDCODED", "dq:MYSQL_DQ_LITERAL", "findOne:SELECT_ONE_ALL_OPTIONAL",
				"findOneWhere11:SELECT_ONE_ALL_OPTIONAL", "findOneSubLimit:SELECT_ONE_ALL_OPTIONAL"), keysOf(fs));
		assertTrue(fs.stream().allMatch(f -> f.line() > 0), "每条命中都要有行号");
	}

	@Test
	@DisplayName("mapper XML 合规样例：注释里提到、字符串里出现、PG 正确写法、共享 schema、有硬条件 / LIMIT / 返回集合 / 聚合 —— 一条都不报")
	void goodXmlIsClean() {
		List<SqlLegacyScanner.Finding> fs = SqlLegacyScanner.scanMapperXml("x/GoodMapper.xml", GOOD_XML,
				sources()::get);
		assertEquals(Set.of(), keysOf(fs), () -> fs.toString());
	}

	@Test
	@DisplayName("Java：注解 SQL（含 <script>）、条件构造器原生片段、整个实体当条件 —— 都抓到")
	void badJavaIsCaught() {
		List<SqlLegacyScanner.Finding> fs = SqlLegacyScanner.scanJava("x/Bad.java", BAD_JAVA);
		assertEquals(new TreeSet<>(Set.of("MYSQL_IFNULL", "MYSQL_LIMIT_COMMA", "MYSQL_DATE_FUNC", "QUERY_BY_ENTITY")),
				rulesOf(fs));
		assertEquals(2, fs.stream().filter(f -> f.rule().equals("QUERY_BY_ENTITY")).count());
	}

	@Test
	@DisplayName("Java 合规样例：注释里的旧写法、SQL 字符串里的字样、Class 参数、空构造、日志文字 —— 一条都不报")
	void goodJavaIsClean() {
		assertEquals(List.of(), SqlLegacyScanner.scanJava("x/Good.java", GOOD_JAVA));
	}

	@Test
	@DisplayName("JdbcTemplate 文件里的 SQL 字面量要扫，且能跨 + 拼接")
	void jdbcTemplateScope() {
		assertEquals(new TreeSet<>(Set.of("MYSQL_IFNULL", "MYSQL_LIMIT_COMMA")),
				rulesOf(SqlLegacyScanner.scanJava("x/Recon.java", PG_JDBC_JAVA)));
	}

	@Test
	@DisplayName("mapper 返回类型解析：泛型、全限定名、数组、调用处不误认")
	void returnTypeParsing() {
		String src = """
				interface M {
				    java.util.Map<String, List<Long>> a(@Param("x") String x);
				    Foo[] b();
				    IPage<Foo> c(IPage<Foo> page);
				    default Foo d() { return a("1") == null ? null : b()[0]; }
				}
				""";
		assertEquals("java.util.Map<String,List<Long>>", SqlLegacyScanner.mapperReturnType(src, "a"));
		assertEquals("Foo[]", SqlLegacyScanner.mapperReturnType(src, "b"));
		assertEquals("IPage<Foo>", SqlLegacyScanner.mapperReturnType(src, "c"));
		assertTrue(SqlLegacyScanner.isSingleRow("java.util.Map<String,List<Long>>"));
		assertFalse(SqlLegacyScanner.isSingleRow("Foo[]"));
		assertFalse(SqlLegacyScanner.isSingleRow("IPage<Foo>"));
		assertFalse(SqlLegacyScanner.isSingleRow("java.util.List<Foo>"));
	}

	@Test
	@DisplayName("门禁比对：临时仓库里新增一条违规 → added 非空；基线一致 → 无差异；基线偏大 → shrunk 非空；target/.claude 不扫")
	void gateDiffOnFixtureRepo(@TempDir Path repo) throws IOException {
		Files.createDirectories(repo.resolve(".git"));
		Path mapperDir = repo.resolve("zhiflow-demo/src/main/resources/mapper");
		Files.createDirectories(mapperDir);
		Files.writeString(mapperDir.resolve("BadMapper.xml"), BAD_XML);
		Path javaDir = repo.resolve("zhiflow-demo/src/main/java/demo");
		Files.createDirectories(javaDir);
		Files.writeString(javaDir.resolve("BadMapper.java"), BAD_MAPPER_JAVA);
		// 这两处必须被跳过：构建产物、worktree 副本
		Path target = repo.resolve("zhiflow-demo/target/classes/mapper");
		Files.createDirectories(target);
		Files.writeString(target.resolve("BadMapper.xml"), BAD_XML);
		Path claude = repo.resolve("zhiflow-demo/.claude/worktrees/x/src/main/resources/mapper");
		Files.createDirectories(claude);
		Files.writeString(claude.resolve("BadMapper.xml"), BAD_XML);

		List<SqlLegacyScanner.Finding> fs = SqlLegacyScanner.scan(repo);
		assertTrue(fs.stream().allMatch(f -> f.file().equals("zhiflow-demo/src/main/resources/mapper/BadMapper.xml")),
				() -> "只应扫 src/main 下那一份：" + fs.stream().map(SqlLegacyScanner.Finding::file).distinct().toList());
		Map<String, Integer> actual = SqlLegacyScanGateTest.count(fs);
		assertEquals(16, actual.size());

		SqlLegacyScanGateTest.Diff fresh = SqlLegacyScanGateTest.diff(actual, Map.of());
		assertEquals(16, fresh.added().size(), "空基线：每条都是新增");

		SqlLegacyScanGateTest.Diff same = SqlLegacyScanGateTest.diff(actual, actual);
		assertTrue(same.added().isEmpty() && same.shrunk().isEmpty(), "基线一致：无差异");

		Map<String, Integer> bigger = new java.util.TreeMap<>(actual);
		bigger.put("MYSQL_IFNULL\tgone/Gone.xml#x", 1);
		SqlLegacyScanGateTest.Diff shrunk = SqlLegacyScanGateTest.diff(actual, bigger);
		assertEquals(List.of("MYSQL_IFNULL\tgone/Gone.xml#x"), shrunk.shrunk(), "存量修掉了必须提示改小基线");

		// 基线文件往返：写出去再读回来，键与条数一致
		Path baseline = repo.resolve("baseline.txt");
		Files.writeString(baseline, SqlLegacyScanGateTest.renderBaseline(actual));
		assertEquals(actual, SqlLegacyScanGateTest.loadBaseline(baseline));
	}
}
