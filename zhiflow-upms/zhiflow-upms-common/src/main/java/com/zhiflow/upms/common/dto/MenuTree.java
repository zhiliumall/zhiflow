package com.zhiflow.upms.common.dto;

import com.zhiflow.common.core.entity.TreeNode;
import com.zhiflow.upms.common.vo.MenuVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 菜单树节点。序列化后直接作为 {@code GET /upms/menu}、{@code /upms/menu/tree}、
 * {@code /upms/menu/all/tree} 三条接口的响应体。
 *
 * <p><b>字段是出口成本，加字段前先想清楚谁读它。</b>2026-08-20 做过一次瘦身，
 * 删掉了 6 个**四个前端仓逐字段 grep 全部零命中**的字段：
 *
 * <pre>
 *   authority / redirect / code   —— 三个构造器一个都没赋过值，所有节点恒为 null
 *   spread                        —— 恒 false，前端从不读
 *   createTime / updateTime       —— 一棵菜单树上的 216×2 个时间戳，无人消费
 * </pre>
 *
 * 判据是**调用方 grep 带对照样本**（拿 name/path/label/permission 这些已知在用的字段
 * 证明 grep 有效），不是「当前值是不是 null」——空值也可能被前端 {@code ?.} 当默认值用。
 * 消费者闭包：管理后台前端（router/utils.ts + permission store + 5 个 upms 页面）、
 * 其它客户端（pages/home/shop-home.vue，只读 children/name）。
 * H5 前端与 PC 端不调用任何菜单端点。
 *
 * <p>⚠️ {@code redirect} 曾被 {@code router/utils.ts:514} 以
 * {@code if (v?.children?.length && !v.redirect)} 读到——但那是**默认值判定**，
 * 后端恒 null 与字段不存在（undefined）在该表达式下逐位等价，故可删。
 *
 * @author
 */
@Data
public class MenuTree extends TreeNode {
	private String icon;
	private String name;
	private String path;
	private String component;
	private String keepAlive;
	private String type;
	private String label;
	private Integer sort;
	private String permission;

	public MenuTree() {
	}

	public MenuTree(String id, String name, String parentId) {
		this.id = id;
		this.parentId = parentId;
		this.name = name;
		this.label = name;
	}

	public MenuTree(String id, String name, MenuTree parent) {
		this.id = id;
		this.parentId = parent.getId();
		this.name = name;
		this.label = name;
	}

	public MenuTree(MenuVO menuVo) {
		this.id = menuVo.getId();
		this.parentId = menuVo.getParentId();
		this.icon = menuVo.getIcon();
		this.name = menuVo.getName();
		this.path = menuVo.getPath();
		this.component = menuVo.getComponent();
		this.type = menuVo.getType();
		this.label = menuVo.getName();
		this.sort = menuVo.getSort();
		this.keepAlive = menuVo.getKeepAlive();
		this.permission = menuVo.getPermission();
	}
}
