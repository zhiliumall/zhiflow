package com.zhiflow.common.core.entity;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * @author
 */
@Data
public class TreeNode {
	protected String id;
	protected String parentId;
	private Integer sort;
	protected List<TreeNode> children = new ArrayList<>();

	public void addChildren(TreeNode treeNode) {
		if (children == null) {
			children = new ArrayList<>();
		}
		children.add(treeNode);
	}

	public List<TreeNode> getChildren() {
		// 空子节点序列化成 null；缓存（Redis）反序列化回来时 children 就是 null，
		// 这里不判空会让命中缓存的 /upms/menu/all/tree 次次 NPE（2026-09-29 压测发现）
		if (children == null || children.isEmpty()) {
			return null;
		}
		return children;
	}
}
