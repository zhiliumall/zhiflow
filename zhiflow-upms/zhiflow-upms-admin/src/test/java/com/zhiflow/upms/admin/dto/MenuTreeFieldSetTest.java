package com.zhiflow.upms.admin.dto;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.zhiflow.upms.common.dto.MenuTree;
import com.zhiflow.upms.common.util.TreeUtil;
import com.zhiflow.upms.common.vo.MenuVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MenuTree} 的**出口字段集合护栏**。
 *
 * <p><b>为什么需要这个测试</b>：{@code MenuTree} 直接就是 {@code GET /upms/menu} /
 * {@code /upms/menu/tree} / {@code /upms/menu/all/tree} 的响应体，且**没有任何 DTO 映射层**
 * ——谁往这个类上加一个字段，谁就直接给全后台每次登录的响应加了 216 份该字段。
 * 2026-08-20 删掉 6 个零消费字段（authority / redirect / code / spread / createTime / updateTime）
 * 时，判据是四个前端仓逐字段 grep。那份判据是一次性的，这个测试是持续的那一半。
 *
 * <p><b>写法上刻意留了对照断言</b>：只断言「被删的 6 个不在」会在序列化整体失灵时静默变绿
 * （例如有人把类改成不可序列化、字段全丢），所以同时断言「已知在用的 9 个必须在」。
 * 缺了正向断言，这个测试就是本仓踩过多次的那种「判据坏了却长得跟一切正常一样」。
 */
class MenuTreeFieldSetTest {

	private static final ObjectMapper MAPPER = new JsonMapper();

	/** 前端确有消费的字段（含 TreeNode 继承来的 3 个）。删任何一个都会打断后台。 */
	private static final Set<String> EXPECTED = Set.of(
			// TreeNode
			"id", "parentId", "children",
			// MenuTree
			"icon", "name", "path", "component", "keepAlive", "type", "label", "sort", "permission");

	/** 2026-08-20 删除；四仓 grep 零命中。重新出现即视为回退。 */
	private static final Set<String> REMOVED = Set.of(
			"authority", "redirect", "code", "spread", "createTime", "updateTime");

	private static MenuVO menuVo(String id, String parentId, String name) {
		MenuVO vo = new MenuVO();
		vo.setId(id);
		vo.setParentId(parentId);
		vo.setName(name);
		vo.setPath("/" + name);
		vo.setComponent("views/" + name + "/index");
		vo.setIcon("icon-" + name);
		vo.setSort(10);
		vo.setType("0");
		vo.setKeepAlive("1");
		vo.setPermission(name + ":view");
		return vo;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> serializeNode(MenuTree node) throws Exception {
		return MAPPER.readValue(MAPPER.writeValueAsString(node), Map.class);
	}

	@Test
	@DisplayName("MenuTree 序列化后的字段集合逐字锁定：9+3 个在用字段全在，6 个已删字段全不在")
	void fieldSetIsLocked() throws Exception {
		Map<String, Object> json = serializeNode(new MenuTree(menuVo("1", "0", "mall")));

		// 正向（对照断言）：判据自身有效性的证明——若序列化整体失灵，这一条先红
		for (String k : EXPECTED) {
			assertTrue(json.containsKey(k), "在用字段丢失：" + k + "，实际字段=" + new TreeSet<>(json.keySet()));
		}
		// 反向：被删字段不得复活
		for (String k : REMOVED) {
			assertFalse(json.containsKey(k),
					"字段 " + k + " 回到了菜单响应里。它 2026-08-20 因四仓 grep 零命中被删除；"
							+ "若确要恢复，先补调用方证据并更新本测试，别直接改断言。");
		}
		// 总数锁死：既防新增又防遗漏，比只查两张名单更严
		assertEquals(EXPECTED, new TreeSet<>(json.keySet()),
				"菜单节点字段集合变了。这是全后台每次登录都要下发 216 份的结构，加字段前请先量出口成本。");
	}

	@Test
	@DisplayName("三条端点共用的两条建树路径产出同一套字段（buildTreeOptimized 与 MenuTree(MenuVO)）")
	void bothConstructionPathsAgree() throws Exception {
		// /upms/menu 与 /upms/menu/tree 走 MenuTree(MenuVO)
		Map<String, Object> viaVo = serializeNode(new MenuTree(menuVo("1", "0", "mall")));

		// /upms/menu/all/tree 走 TreeUtil.buildTreeOptimized(List<SysMenu>)
		com.zhiflow.upms.common.entity.SysMenu sysMenu =
				new com.zhiflow.upms.common.entity.SysMenu();
		sysMenu.setId("1");
		sysMenu.setParentId("0");
		sysMenu.setName("mall");
		sysMenu.setPath("/mall");
		sysMenu.setComponent("views/mall/index");
		sysMenu.setIcon("icon-mall");
		sysMenu.setSort(10);
		sysMenu.setType("0");
		sysMenu.setKeepAlive("1");
		sysMenu.setPermission("mall:view");
		List<MenuTree> built = TreeUtil.buildTreeOptimized(List.of(sysMenu), "0");
		assertEquals(1, built.size(), "根节点应被建出来；parentId='0' 是 CommonConstants.PARENT_ID 的值");

		assertEquals(new TreeSet<>(viaVo.keySet()), new TreeSet<>(serializeNode(built.get(0)).keySet()),
				"两条建树路径的字段集合必须一致，否则同一个前端解析逻辑会在不同端点上看到不同形状");
	}
}
