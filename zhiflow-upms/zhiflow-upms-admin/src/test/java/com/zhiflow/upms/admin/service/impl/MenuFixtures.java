package com.zhiflow.upms.admin.service.impl;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.upms.common.vo.MenuVO;
import org.junit.jupiter.api.Assumptions;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 菜单快照测试用的两角色菜单行。
 *
 * <p>{@link #synthetic()} 按真实数据的形状合成（固定随机种子，结果确定）：
 * <ul>
 *   <li>角色 A 约 1100 行、约 20% 是菜单（type=0）其余是按钮；</li>
 *   <li>角色 B 大半是 A 的子集，另有少量独有行，
 *       覆盖「两角色合并」这条路径；</li>
 *   <li>兄弟节点间 sort 大量重复（稳定排序的并列顺序由 HashSet 迭代序决定，最容易出现逐字节差异的地方）；</li>
 *   <li>有 parentId 指向不存在节点的孤儿、有 parentId=-1 的按钮（真实数据里有，建树时被丢掉）；</li>
 *   <li>约 20% 按钮 permission 为 null、少量为 ""、少量重复；</li>
 *   <li>中文名、可空的 path/icon/component、带纳秒的时间。</li>
 * </ul>
 * 每个列表都按 {@code sort DESC} 排（与 {@code listMenusByRoleId} 的 ORDER BY 一致）。
 *
 * <p>{@link #real(RedisSerializer)} 读从 Redis 按字节原样导出的两份缓存值
 * （{@code -Dmenu.fixtures.dir=<目录>}，文件名 {@code public_menu_cache__<roleId>_menu.json}）；没给目录就跳过。
 */
final class MenuFixtures {

	static final String ROLE_A = "1";

	static final String ROLE_B = "1900000000000000005";

	private MenuFixtures() {
	}

	static Map<String, List<MenuVO>> synthetic() {
		Random rnd = new Random(20260930L);
		List<MenuVO> menus = new ArrayList<>();
		List<MenuVO> rows = new ArrayList<>();
		long nextId = 1_450_000_000_000_000_000L;

		// 一级菜单
		for (int i = 0; i < 28; i++) {
			MenuVO m = row(rnd, String.valueOf(nextId += 1 + rnd.nextInt(5000)), CommonConstants.PARENT_ID, "0",
					"一级菜单" + i, rnd.nextInt(6), null);
			menus.add(m);
			rows.add(m);
		}
		// 二、三级菜单：父节点从已有菜单里随机挑
		for (int i = 0; i < 190; i++) {
			MenuVO parent = menus.get(rnd.nextInt(menus.size()));
			String perm = rnd.nextInt(3) == 0 ? null : "mall:page" + i + ":index";
			MenuVO m = row(rnd, String.valueOf(nextId += 1 + rnd.nextInt(5000)), parent.getId(), "0",
					"菜单" + i, rnd.nextInt(8), perm);
			menus.add(m);
			rows.add(m);
		}
		// 孤儿菜单（父节点不在集合里）
		for (int i = 0; i < 5; i++) {
			rows.add(row(rnd, String.valueOf(nextId += 1 + rnd.nextInt(5000)), "1880000000000000" + (100 + i), "0",
					"孤儿菜单" + i, rnd.nextInt(8), "orphan:" + i));
		}
		// 按钮
		List<String> issuedPerms = new ArrayList<>();
		for (int i = 0; i < 870; i++) {
			MenuVO parent = menus.get(rnd.nextInt(menus.size()));
			String perm;
			int roll = rnd.nextInt(100);
			if (roll < 20) {
				perm = null;
			} else if (roll < 22) {
				perm = "";
			} else if (roll < 26 && !issuedPerms.isEmpty()) {
				perm = issuedPerms.get(rnd.nextInt(issuedPerms.size()));
			} else {
				perm = "mall_res" + rnd.nextInt(400) + "_" + new String[]{"add", "edit", "del", "export", "view"}[i % 5] + i;
				issuedPerms.add(perm);
			}
			String parentId = i % 97 == 0 ? CommonConstants.SYSTEM_TENANT_ID : parent.getId();
			rows.add(row(rnd, String.valueOf(nextId += 1 + rnd.nextInt(5000)), parentId, "1",
					"按钮" + i, rnd.nextInt(4), perm));
		}

		List<MenuVO> roleA = new ArrayList<>(rows);
		List<MenuVO> roleB = new ArrayList<>();
		for (MenuVO m : rows) {
			if (rnd.nextInt(100) < 55) {
				roleB.add(m);
			}
		}
		// B 独有：几个挂在已有菜单下的菜单与按钮
		for (int i = 0; i < 30; i++) {
			MenuVO parent = menus.get(rnd.nextInt(menus.size()));
			boolean isMenu = i < 6;
			roleB.add(row(rnd, String.valueOf(nextId += 1 + rnd.nextInt(5000)), parent.getId(), isMenu ? "0" : "1",
					"B独有" + i, rnd.nextInt(6), isMenu ? null : "order_only_" + i));
		}

		Map<String, List<MenuVO>> byRole = new LinkedHashMap<>();
		byRole.put(ROLE_A, sortDesc(roleA));
		byRole.put(ROLE_B, sortDesc(roleB));
		return byRole;
	}

	@SuppressWarnings("unchecked")
	static Map<String, List<MenuVO>> real(RedisSerializer<Object> serializer) throws Exception {
		Path dir = Path.of(System.getProperty("menu.fixtures.dir", "/nonexistent"));
		Map<String, List<MenuVO>> byRole = new LinkedHashMap<>();
		for (String role : List.of(ROLE_A, ROLE_B)) {
			Path p = dir.resolve("public_menu_cache__" + role + "_menu.json");
			Assumptions.assumeTrue(Files.exists(p), "缺生产样本 " + p + "（用 -Dmenu.fixtures.dir 指定目录）");
			byRole.put(role, (List<MenuVO>) serializer.deserialize(Files.readAllBytes(p)));
		}
		return byRole;
	}

	private static List<MenuVO> sortDesc(List<MenuVO> rows) {
		List<MenuVO> sorted = new ArrayList<>(rows);
		sorted.sort(Comparator.comparing(MenuVO::getSort).reversed());
		return sorted;
	}

	private static MenuVO row(Random rnd, String id, String parentId, String type, String name, int sort,
			String permission) {
		MenuVO vo = new MenuVO();
		vo.setId(id);
		vo.setParentId(parentId);
		vo.setType(type);
		vo.setName(name);
		vo.setSort(sort);
		vo.setPermission(permission);
		vo.setPath(rnd.nextInt(4) == 0 ? null : "/mall/" + name + "/" + id.substring(id.length() - 4));
		vo.setComponent("0".equals(type) && rnd.nextBoolean() ? "views/mall/" + id + "/index" : null);
		vo.setIcon(rnd.nextInt(3) == 0 ? "ep:setting" : null);
		vo.setKeepAlive(rnd.nextBoolean() ? "1" : "0");
		vo.setDelFlag("0");
		vo.setCreateTime(LocalDateTime.of(2025, 1, 1, 0, 0).plusSeconds(rnd.nextInt(30_000_000)));
		vo.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 0).plusNanos(rnd.nextLong(1L << 50)));
		return vo;
	}
}
