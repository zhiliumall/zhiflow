package com.zhiflow.upms.admin.config;

import com.zhiflow.common.core.constant.CacheConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 首启随机管理员口令：基线里的后台账号不带口令哈希，应用首次启动时给每个还没有口令的账号
 * 生成一个随机口令、打印到启动日志，并标记「下次登录前必须修改密码」。
 *
 * <p>只处理口令为空的账号，已有口令的一律不动，所以每次启动都可以跑。多实例同时启动时靠条件更新
 * （{@code WHERE password IS NULL OR password = ''}）保证只有一个实例写成功，也只有写成功的那个打印口令。
 *
 * <p>标记之后登录会被拒绝（凭证过期），前端引导到「首次登录修改密码」，改完才能正常登录。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "upms.first-boot-password.enabled", havingValue = "true", matchIfMissing = true)
public class FirstBootAdminPasswordInitializer {

	/** 排在 {@link SharedSchemaMigrator} 之后：依赖它加的 password_change_required 列。 */
	static final int ORDER = SharedSchemaMigrator.ORDER + 10;

	static final int PASSWORD_LENGTH = 16;

	/** 去掉了 0/O、1/l/I 这类容易抄错的字符。 */
	private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();

	private static final Pattern SCHEMA_NAME = Pattern.compile("[a-z_][a-z0-9_]*");

	private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

	private final JdbcTemplate jdbc;

	private final CacheManager cacheManager;

	private final String schema;

	private final SecureRandom random = new SecureRandom();

	public FirstBootAdminPasswordInitializer(DataSource dataSource, CacheManager cacheManager,
			@Value("${upms.schema-migrator.schema:zhiflow_upms}") String schema) {
		if (!SCHEMA_NAME.matcher(schema).matches()) {
			throw new IllegalArgumentException("upms.schema-migrator.schema 不是合法的 schema 名: " + schema);
		}
		this.jdbc = new JdbcTemplate(dataSource);
		this.cacheManager = cacheManager;
		this.schema = schema;
	}

	@Order(ORDER)
	@EventListener(ApplicationReadyEvent.class)
	public void initialize() {
		List<Map<String, Object>> users;
		try {
			users = jdbc.queryForList("SELECT id, username FROM " + schema
					+ ".sys_user WHERE (password IS NULL OR password = '') AND del_flag = '0' ORDER BY id");
		}
		catch (RuntimeException e) {
			log.error("[首启口令] 查询待初始化的后台账号失败，跳过", e);
			return;
		}
		for (Map<String, Object> user : users) {
			String id = String.valueOf(user.get("id"));
			String username = String.valueOf(user.get("username"));
			String password = generate();
			int updated = jdbc.update("UPDATE " + schema + ".sys_user SET password = ?, password_change_required = '1', "
					+ "update_time = now() WHERE id = ? AND (password IS NULL OR password = '')", ENCODER.encode(password), id);
			if (updated != 1) {
				// 别的实例先写了，以它打印的为准
				continue;
			}
			evictUserCache(username);
			log.warn("""

					================================================================
					  首次启动：已为后台账号生成随机口令（只显示这一次）
					    账号：{}
					    口令：{}
					  首次登录后必须修改密码。
					================================================================""", username, password);
		}
	}

	String generate() {
		char[] out = new char[PASSWORD_LENGTH];
		for (int i = 0; i < out.length; i++) {
			out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
		}
		return new String(out);
	}

	private void evictUserCache(String username) {
		Cache cache = cacheManager.getCache(CacheConstants.USER_CACHE);
		if (cache != null) {
			cache.evict(username);
		}
	}
}
