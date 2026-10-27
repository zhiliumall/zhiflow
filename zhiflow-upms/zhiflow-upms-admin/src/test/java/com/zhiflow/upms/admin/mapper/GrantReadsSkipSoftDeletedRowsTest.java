package com.zhiflow.upms.admin.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读「谁有什么权限」的手写 SQL 必须跳过软删的授权行（2026-09-26）。
 *
 * <p>2026-07-26 起 {@code SysRoleMenu} / {@code SysUserRole} / {@code SysUserTenant} 都继承带 {@code @TableLogic}
 * 的基类，后台「分配角色权限」「编辑用户角色」「平台管理员绑定租户」都是「先 remove 旧行、再插新行」，remove 变成了
 * {@code del_flag='1'}。但读权限、读角色、读可切换租户的 XML 不过滤 del_flag——撤掉的授权行照样生效，
 * 等于后台撤销权限从来不起作用。这里扫 mapper/upms 下全部 select：凡是读到这三张表，就必须带上
 * {@code <别名>.del_flag = '0'}。
 */
class GrantReadsSkipSoftDeletedRowsTest {

	private static final Pattern GRANT_TABLE = Pattern.compile(
			"\\b(sys_role_menu|sys_user_role|sys_user_tenant)\\b(?!\\.)(?:\\s+(?:AS\\s+)?([A-Za-z_][A-Za-z0-9_]*))?",
			Pattern.CASE_INSENSITIVE);
	private static final Set<String> NOT_ALIAS = Set.of("where", "on", "left", "right", "inner", "join", "and", "or",
			"order", "group", "limit", "set", "values");

	@Test
	void everySelectOnAGrantTableFiltersSoftDeletedRows() throws IOException {
		List<String> missing = new ArrayList<>();
		int checked = 0;
		for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/upms/*.xml")) {
			String xml;
			try (InputStream in = r.getInputStream()) {
				xml = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
			}
			Matcher sel = Pattern.compile("<select id=\"([^\"]+)\"[^>]*>(.*?)</select>", Pattern.DOTALL).matcher(xml);
			while (sel.find()) {
				Matcher t = GRANT_TABLE.matcher(sel.group(2));
				while (t.find()) {
					String alias = t.group(2) == null || NOT_ALIAS.contains(t.group(2).toLowerCase()) ? t.group(1) : t.group(2);
					checked++;
					if (!Pattern.compile("\\b" + Pattern.quote(alias) + "\\.del_flag\\s*=\\s*'0'").matcher(sel.group(2)).find()) {
						missing.add(r.getFilename() + "#" + sel.group(1) + " (" + t.group(1) + " " + alias + ")");
					}
				}
			}
		}
		// 当前 5 个文件里 11 处读到这三张表；数字变了说明有新语句，确认它也被检查到了再改这里
		assertEquals(11, checked, "读授权表的 select 数量变了");
		assertTrue(missing.isEmpty(), "这些语句会把撤销掉的授权当成有效：" + missing);
	}
}
