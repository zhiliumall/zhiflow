package com.zhiflow.weixin.admin.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公众号粉丝 / 消息分表的建表语句不得含 DROP（2026-09-26）。
 *
 * <p>upms 的 {@code POST /tenant/database/init/{id}} 补偿会调到这里；旧写法
 * {@code DROP TABLE IF EXISTS ... CASCADE} 会把存量租户的 {@code wx_user_{tid}} / {@code wx_msg_{tid}} 清空。
 */
class WxShardDdlNeverDropsTest {

	private static final String XML = "mapper/weixin/WxUserMapper.xml";

	@Test
	void createTenantDataBaseTablesNeverDrops() throws IOException {
		String xml;
		try (InputStream in = WxShardDdlNeverDropsTest.class.getClassLoader().getResourceAsStream(XML)) {
			assertNotNull(in, XML + " 不在 classpath 上");
			xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		Matcher m = Pattern.compile("<select id=\"createTenantDataBaseTables\">(.*?)</select>", Pattern.DOTALL).matcher(xml);
		assertTrue(m.find(), "找不到语句 createTenantDataBaseTables");
		String body = m.group(1);
		assertFalse(body.replaceAll("(?s)<!--.*?-->", "").toUpperCase().contains("DROP"), body);
		assertTrue(body.contains("CREATE TABLE IF NOT EXISTS"), body);
	}
}
