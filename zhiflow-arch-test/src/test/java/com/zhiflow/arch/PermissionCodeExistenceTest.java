package com.zhiflow.arch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「幽灵权限码」门禁：{@code @PreAuthorize("@ato.hasAuthority('x')")} 里引用的码，
 * 必须在基线种子的 {@code sys_menu.permission} 里存在，并且授给了角色。
 *
 * <h2>这条规则要抓的缺陷长什么样</h2>
 * <p>代码引用了一个库里没有的码 ⇒ 该端点对<b>所有人（含超管）永久 403</b>，
 * 前端按钮也永远不渲染。它<b>没有任何运行期信号</b>：不抛异常、不打日志，表现就是「这个功能没做」。
 * 码存在但没有角色持有，效果一样。
 *
 * <h2>比对的是什么</h2>
 * <p>开源版的菜单与授权只有一个来源：upms 基线 {@code db/migration/upms/V1__baseline.sql} 里的
 * {@code sys_menu} / {@code sys_role_menu} 种子。新增权限码要在新的迁移脚本（V2……）里建码并授权；
 * 本门禁读 upms 迁移目录下的全部脚本。
 */
class PermissionCodeExistenceTest {

	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	private static final Path UPMS_MIGRATIONS =
			REPO_ROOT.resolve("zhiflow-upms/zhiflow-upms-admin/src/main/resources/db/migration/upms");

	/**
	 * {@code hasAuthority('x')} / {@code hasAnyAuthority('x','y')} 里的单引号字面量。
	 * 注解值本身是双引号字符串，码是里面的单引号串，所以直接抓单引号内容。
	 */
	private static final Pattern AUTHORITY_LITERAL =
			Pattern.compile("has(?:Any)?Authority\\s*\\(([^)]*)\\)");

	private static final Pattern SINGLE_QUOTED = Pattern.compile("'([^']+)'");

	/** 种子行：{@code INSERT INTO sys_menu (id, name, permission, ...) VALUES ('id', 'name', 'permission', ...)}。 */
	private static final Pattern MENU_ROW = Pattern.compile(
			"INSERT INTO sys_menu \\(id, name, permission,[^)]*\\) VALUES \\('([^']*)', '(?:[^']|'')*', '([^']*)'");

	/** {@code INSERT INTO sys_role_menu (id, role_id, menu_id, ...) VALUES ('id', 'role', 'menu', ...)}。 */
	private static final Pattern ROLE_MENU_ROW = Pattern.compile(
			"INSERT INTO sys_role_menu \\(id, role_id, menu_id,[^)]*\\) VALUES \\('[^']*', '[^']*', '([^']*)'");

	/** 文档 / 注释里的占位示例，不是真码（stripComments 之后仍可能残留在字符串常量里）。 */
	private static final Set<String> NOT_CODES = Set.of("xxx", "SCOPE_xxx");

	@Test
	@DisplayName("@PreAuthorize 引用的权限码必须在基线 sys_menu 里存在且授给了角色（否则对所有人永久 403 且零运行期信号）")
	void authorityLiteralsMustExistInSysMenu() throws IOException {
		Map<String, String> menuIdByCode = new HashMap<>();
		Set<String> grantedMenuIds = new HashSet<>();
		try (Stream<Path> scripts = Files.list(UPMS_MIGRATIONS)) {
			for (Path script : scripts.filter(f -> f.getFileName().toString().endsWith(".sql")).toList()) {
				String sql = Files.readString(script, StandardCharsets.UTF_8);
				Matcher menu = MENU_ROW.matcher(sql);
				while (menu.find()) {
					if (!menu.group(2).isEmpty()) {
						menuIdByCode.put(menu.group(2), menu.group(1));
					}
				}
				Matcher grant = ROLE_MENU_ROW.matcher(sql);
				while (grant.find()) {
					grantedMenuIds.add(grant.group(1));
				}
			}
		}
		// 对照组：解析坏了（种子改格式）会让全部码变成幽灵码，先拒绝判定
		assertTrue(menuIdByCode.size() > 50 && grantedMenuIds.size() > 50,
				"从 " + UPMS_MIGRATIONS + " 只解析出 " + menuIdByCode.size() + " 个权限码、"
						+ grantedMenuIds.size() + " 条授权 —— 多半是种子 INSERT 的格式变了，先修本测试的解析");

		// hasAuthority('a','b') 是任一即可：一处调用里只要有一个码存在且授了权，端点就能用；
		// 同一调用里其余不存在的码是死写法（指向已删功能），单独报出来让人删掉
		Set<String> ghosts = new TreeSet<>();
		Set<String> orphans = new TreeSet<>();
		Set<String> deadAlternatives = new TreeSet<>();
		for (Path file : controllerSources()) {
			String source = JavaSource.stripComments(Files.readString(file, StandardCharsets.UTF_8));
			Matcher call = AUTHORITY_LITERAL.matcher(source);
			while (call.find()) {
				List<String> codes = new ArrayList<>();
				Matcher literal = SINGLE_QUOTED.matcher(call.group(1));
				while (literal.find()) {
					if (!NOT_CODES.contains(literal.group(1))) {
						codes.add(literal.group(1));
					}
				}
				if (codes.isEmpty()) {
					continue;
				}
				String where = "    ← " + file.getFileName();
				List<String> missing = codes.stream().filter(c -> !menuIdByCode.containsKey(c)).toList();
				if (missing.size() == codes.size()) {
					ghosts.add(String.join("','", codes) + where);
					continue;
				}
				missing.forEach(c -> deadAlternatives.add(c + where));
				boolean usable = codes.stream()
					.map(menuIdByCode::get)
					.anyMatch(id -> id != null && grantedMenuIds.contains(id));
				if (!usable) {
					orphans.add(String.join("','", codes) + where);
				}
			}
		}

		assertTrue(ghosts.isEmpty(), () -> """
				下列调用引用的权限码在基线 sys_menu 里一个都不存在：

				%s

				这些端点对所有人（含超管）永久 403，前端按钮也不会渲染。
				先查是不是有改了名的同族码（修法是代码对齐既有码）；真漏了就在新的 upms 迁移脚本里建按钮菜单并授给 ROLE_ADMIN（role 1）。
				接口属于已删功能的，连同接口一起删。
				""".formatted(String.join("\n", ghosts)));

		assertTrue(orphans.isEmpty(), () -> """
				下列调用引用的权限码都没有授给任何角色：

				%s

				挂了这些码的端点对所有人都是 403。在迁移脚本里补 sys_role_menu。
				""".formatted(String.join("\n", orphans)));

		assertTrue(deadAlternatives.isEmpty(), () -> """
				下列码与同一处的其他码是「任一即可」，但基线里不存在，是指向已删功能的死写法，删掉：

				%s
				""".formatted(String.join("\n", deadAlternatives)));
	}

	/**
	 * 只扫 controller：{@code @PreAuthorize} 也会出现在 service 上，但那不是本规则的目标，
	 * 而且业务模块里带 {@code hasAuthority} 的测试桩会制造噪声。
	 */
	private static List<Path> controllerSources() throws IOException {
		return Files.walk(REPO_ROOT, 12)
				.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().endsWith("Controller.java"))
				.filter(p -> {
					String s = p.toString();
					return s.contains("/src/main/java/") && !s.contains("/target/");
				})
				.toList();
	}
}
