package com.zhiflow.common.core.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 空子节点序列化成 null，从 Redis 缓存反序列化回来 children 就是 null。
 * 2026-09-29 压测发现 /upms/menu/all/tree 命中缓存后次次 NPE（TreeNode.getChildren）。
 */
class TreeNodeNullChildrenTest {

	@Test
	void getChildren_whenChildrenNull_returnsNullInsteadOfThrowing() {
		TreeNode node = new TreeNode();
		node.setChildren(null);
		assertNull(node.getChildren());
	}

	@Test
	void addChildren_whenChildrenNull_createsList() {
		TreeNode node = new TreeNode();
		node.setChildren(null);
		node.addChildren(new TreeNode());
		assertEquals(1, node.getChildren().size());
	}
}
