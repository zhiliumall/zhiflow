package com.zhiflow.upms.admin.mapper;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.upms.admin.projection.UpmsInsideProjections;
import com.zhiflow.upms.common.dto.SysUserQuery;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SysUserMapper.xml#listSysUser} 必须只返回未删除的系统用户（2026-09-29）。
 *
 * <h2>生产事故</h2>
 * <p>当初的事故：按订单短信开关取店员手机号时，这条 SQL 不看 del_flag，已删账号照样被当成在职店员返回。
 * 开源版去掉了店员短信 / 推送 / 客服这些开关，剩下按 id、账号类型查；已删账号同样不该返回，
 * 投影 {@code UpmsInsideProjections#userQuery} 也不带 delFlag，所以过滤仍写死在 SQL 里。
 *
 * <h2>为什么同时断言「文本」和「渲染后的 SQL」</h2>
 * <ul>
 *   <li>文本：去掉所有 {@code <if>} 之后条件仍在 ⇒ 它不依赖任何参数，是无条件生效的。
 *       只查「XML 里有这个字符串」会被「写进了某个 {@code <if>}」骗过。</li>
 *   <li>渲染：用 MyBatis 自己的动态 SQL 引擎按每种查询参数各渲染一遍，
 *       断言 WHERE 里有它 —— 这才是数据库真正收到的那条语句。</li>
 * </ul>
 * <p>这个 XML 里有好几条 {@code <where>}（09-14 就因为改到了 {@code getUserVosPage} 那条出过事），
 * 所以只截 {@code <select id="listSysUser">} 这一块。
 */
class ListSysUserSkipsDeletedUsersTest {

	private static final String MAPPER = "mapper/upms/SysUserMapper.xml";

	private static final Pattern NOT_DELETED = Pattern.compile("sys_user\\.del_flag\\s*=\\s*'0'");

	@Test
	@DisplayName("listSysUser 的 del_flag='0' 不在任何 <if> 里（无条件生效）")
	void deletedFilterIsUnconditional() throws IOException {
		String select = listSysUserBody();
		String withoutIfs = select.replaceAll("(?s)<if\\b[^>]*>.*?</if>", "");
		assertTrue(NOT_DELETED.matcher(withoutIfs).find(),
				"listSysUser 没有无条件的 sys_user.del_flag = '0'——已删账号会被当成在职店员/客服返回给调用方");
	}

	@Test
	@DisplayName("按每种查询参数渲染，WHERE 里都带 del_flag='0'")
	void renderedSqlForEveryCallerFiltersDeletedUsers() throws IOException {
		String select = listSysUserBody();
		Map<String, SysUserQuery> callers = new LinkedHashMap<>();

		SysUserQuery byType = new SysUserQuery();
		byType.setType(CommonConstants.USER_TYPE_1);
		callers.put("按账号类型", byType);

		SysUserQuery byId = new SysUserQuery();
		byId.setId("10086");
		callers.put("按用户 id", byId);

		callers.put("不带任何条件", new SysUserQuery());

		for (Map.Entry<String, SysUserQuery> caller : callers.entrySet()) {
			String sql = render(select, caller.getValue());
			int where = sql.toUpperCase().indexOf("WHERE");
			assertTrue(where >= 0, caller.getKey() + "：渲染出的 SQL 没有 WHERE —— " + sql);
			assertTrue(NOT_DELETED.matcher(sql.substring(where)).find(),
					caller.getKey() + "：数据库收到的语句不过滤已删用户 —— " + sql);
		}
	}

	/**
	 * 判据自检：渲染器确实在处理动态标签（设了的参数出现、没设的不出现）。
	 * 否则上一条可能是在对一段「什么都没展开」的文本做断言，绿了也不说明任何事。
	 */
	@Test
	@DisplayName("自检：渲染器真的按参数展开了 <if>")
	void rendererActuallyEvaluatesDynamicTags() throws IOException {
		SysUserQuery byType = new SysUserQuery();
		byType.setType(CommonConstants.USER_TYPE_1);
		String sql = render(listSysUserBody(), byType);
		assertTrue(sql.contains("sys_user.type = ?"), sql);
		assertFalse(sql.contains("sys_user.id = ?"), "没传 id 却渲染出了它的条件：" + sql);
	}

	/** 与生产同一条参数链：SysUserQuery → UpmsInsideProjections#userQuery → @Param("query") SysUser。 */
	private static String render(String selectBody, SysUserQuery query) {
		var source = new XMLLanguageDriver().createSqlSource(new Configuration(),
				"<script>" + selectBody + "</script>", Map.class);
		Map<String, Object> params = Map.of("query", UpmsInsideProjections.userQuery(query));
		return source.getBoundSql(params).getSql().replaceAll("\\s+", " ");
	}

	/** 只取 listSysUser 这一块的内部内容（去掉注释），避免别的 select 里有同样的字符串造成假绿。 */
	private static String listSysUserBody() throws IOException {
		String xml;
		try (InputStream in = ListSysUserSkipsDeletedUsersTest.class.getClassLoader().getResourceAsStream(MAPPER)) {
			if (in == null) {
				throw new IOException(MAPPER + " 不在测试 classpath 上——本护栏的判据已失效");
			}
			xml = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
		}
		int start = xml.indexOf("<select id=\"listSysUser\"");
		if (start < 0) {
			throw new AssertionError("SysUserMapper.xml 里找不到 <select id=\"listSysUser\">——先修判据再谈结论");
		}
		int bodyStart = xml.indexOf('>', start) + 1;
		int end = xml.indexOf("</select>", bodyStart);
		if (end < 0) {
			throw new AssertionError("<select id=\"listSysUser\"> 没有闭合标签");
		}
		return xml.substring(bodyStart, end);
	}

}
