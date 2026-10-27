package com.zhiflow.upms.admin.config;

import com.zhiflow.common.core.constant.CacheConstants;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首启随机口令：只给没有口令的账号生成、置改密标记、清用户缓存；已有口令的不动；再跑一遍不变。
 */
class FirstBootAdminPasswordInitializerTest {

	private JdbcTemplate jdbc;

	private ConcurrentMapCacheManager cacheManager;

	private FirstBootAdminPasswordInitializer initializer;

	@BeforeEach
	void setUp() {
		JdbcDataSource ds = new JdbcDataSource();
		ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
		jdbc = new JdbcTemplate(ds);
		jdbc.execute("CREATE SCHEMA upms_test");
		jdbc.execute("CREATE TABLE upms_test.sys_user (id varchar(64) PRIMARY KEY, username varchar(64), password varchar(255), "
				+ "del_flag char(1) DEFAULT '0', password_change_required char(1) DEFAULT '0' NOT NULL, update_time timestamp)");
		jdbc.update("INSERT INTO upms_test.sys_user (id, username, password) VALUES ('1', 'admin', NULL)");
		jdbc.update("INSERT INTO upms_test.sys_user (id, username, password) VALUES ('2', 'system', '')");
		jdbc.update("INSERT INTO upms_test.sys_user (id, username, password) VALUES ('3', 'operator', 'existing-hash')");
		jdbc.update("INSERT INTO upms_test.sys_user (id, username, password, del_flag) VALUES ('4', 'deleted', NULL, '1')");
		cacheManager = new ConcurrentMapCacheManager(CacheConstants.USER_CACHE);
		cacheManager.getCache(CacheConstants.USER_CACHE).put("admin", "stale");
		initializer = new FirstBootAdminPasswordInitializer(ds, cacheManager, "upms_test");
	}

	@Test
	@DisplayName("没有口令的账号：写入 BCrypt 哈希、置改密标记、清缓存；已有口令与已删除的不动")
	void initializesOnlyUsersWithoutPassword() {
		initializer.initialize();

		Map<String, Object> admin = row("1");
		assertTrue(admin.get("password").toString().startsWith("$2"), "应是 BCrypt 哈希");
		assertEquals("1", admin.get("password_change_required"));
		assertEquals("1", row("2").get("password_change_required"));
		assertNull(cacheManager.getCache(CacheConstants.USER_CACHE).get("admin"), "旧的用户缓存要清掉");

		assertEquals("existing-hash", row("3").get("password"));
		assertEquals("0", row("3").get("password_change_required"));
		assertNull(row("4").get("password"));
	}

	@Test
	@DisplayName("再启动一次：已初始化的账号不再改（口令只生成、打印一次）")
	void secondRunChangesNothing() {
		initializer.initialize();
		Object hash = row("1").get("password");

		initializer.initialize();

		assertEquals(hash, row("1").get("password"));
	}

	@Test
	@DisplayName("生成的口令：16 位、只含去歧义字符、每次不同；BCrypt 能校验")
	void generatedPasswords() {
		String a = initializer.generate();
		String b = initializer.generate();
		assertEquals(FirstBootAdminPasswordInitializer.PASSWORD_LENGTH, a.length());
		assertTrue(a.matches("[A-HJ-NP-Za-km-z2-9]+"), a);
		assertNotEquals(a, b);
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
		assertTrue(encoder.matches(a, encoder.encode(a)));
	}

	@Test
	@DisplayName("schema 名只接受小写标识符（拼进 SQL 前校验）")
	void rejectsUnsafeSchemaName() {
		assertThrows(IllegalArgumentException.class,
				() -> new FirstBootAdminPasswordInitializer(new JdbcDataSource(), cacheManager, "upms; drop table x"));
	}

	private Map<String, Object> row(String id) {
		return jdbc.queryForMap("SELECT password, password_change_required FROM upms_test.sys_user WHERE id = ?", id);
	}
}
