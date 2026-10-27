package com.zhiflow.upms.common.util;

import com.zhiflow.upms.common.dto.MenuTree;
import com.zhiflow.common.core.entity.TreeNode;
import com.zhiflow.upms.common.entity.SysMenu;
import lombok.experimental.UtilityClass;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author
 */
@UtilityClass
public class TreeUtil {
	/**
	 * 两层循环实现建树
	 *
	 * @param treeNodes 传入的树节点列表
	 * @return
	 */
	public <T extends TreeNode> List<T> build(List<T> treeNodes, Object root) {
		List<T> trees = new ArrayList<>();
		for (T treeNode : treeNodes) {
			if (root.equals(treeNode.getParentId())) {
				trees.add(treeNode);
//				trees.sort(Comparator.comparing(TreeNode::getSort));
			}
			for (T it : treeNodes) {
				if (it.getParentId().equals(treeNode.getId())) {
					treeNode.addChildren(it);
//					treeNode.getChildren().sort(Comparator.comparing(TreeNode::getSort));
				}
			}
		}
		return trees;
	}

	/**
	 * 使用递归方法建树
	 *
	 * @param treeNodes
	 * @return
	 */
	public <T extends TreeNode> List<T> buildByRecursive(List<T> treeNodes, Object root) {
		List<T> trees = new ArrayList<T>();
		for (T treeNode : treeNodes) {
			if (root.equals(treeNode.getParentId())) {
				trees.add(findChildren(treeNode, treeNodes));
			}
		}
		return trees;
	}

	/**
	 * 递归查找子节点
	 *
	 * @param treeNodes
	 * @return
	 */
	public <T extends TreeNode> T findChildren(T treeNode, List<T> treeNodes) {
		for (T it : treeNodes) {
			if (treeNode.getId() == it.getParentId()) {
				if (treeNode.getChildren() == null) {
					treeNode.setChildren(new ArrayList<>());
				}
				treeNode.addChildren(findChildren(it, treeNodes));
			}
		}
		return treeNode;
	}

	/**
	 * 通过sysMenu创建树形节点
	 *
	 * @param menus
	 * @param root
	 * @return
	 */
	public List<MenuTree> buildTree(List<SysMenu> menus, String root) {
		List<MenuTree> trees = new ArrayList<>();
		MenuTree node;
		for (SysMenu menu : menus) {
			node = new MenuTree();
			node.setType(menu.getType());
			node.setId(menu.getId());
			node.setParentId(menu.getParentId());
			node.setName(menu.getName());
			node.setPath(menu.getPath());
			node.setPermission(menu.getPermission());
			node.setLabel(menu.getName());
			node.setComponent(menu.getComponent());
			node.setIcon(menu.getIcon());
			node.setSort(menu.getSort());
			node.setKeepAlive(menu.getKeepAlive());
			trees.add(node);
		}
		return TreeUtil.build(trees, root);
	}

	/**
	 * 高性能树形结构构建方法
	 * 使用 HashMap 优化查找，将时间复杂度从 O(n²) 优化到 O(n)
	 *
	 * @param <T> 树节点类型，必须实现 TreeNode 接口
	 * @param treeNodes 传入的树节点列表
	 * @param rootId 根节点ID
	 * @return 构建好的树形结构列表
	 * @throws IllegalArgumentException 如果参数为空
	 */
	public <T extends TreeNode> List<T> buildOptimized(List<T> treeNodes, Object rootId) {
		// 参数校���
		if (treeNodes == null) {
			throw new IllegalArgumentException("treeNodes cannot be null");
		}
		if (rootId == null) {
			throw new IllegalArgumentException("rootId cannot be null");
		}

		// 1. 使用 HashMap 存储所有节点，O(n) 时间复杂度
		Map<Object, T> nodeMap = new HashMap<>(treeNodes.size());
		for (T treeNode : treeNodes) {
			nodeMap.put(treeNode.getId(), treeNode);
		}

		// 2. 构建父子关系，O(n) 时间复杂度
		List<T> trees = new ArrayList<>();
		for (T node : treeNodes) {
			Object parentId = node.getParentId();

			if (rootId.equals(parentId)) {
				// 根节点
				trees.add(node);
			} else {
				// 查找父节点
				T parent = nodeMap.get(parentId);
				if (parent != null) {
					parent.addChildren(node);
				}
			}
		}

		return trees;
	}

	/**
	 * 通过sysMenu创建树形节点（优化版本）
	 * 使用高性能的 buildOptimized 方法
	 *
	 * @param menus 菜单列表
	 * @param root 根节点ID
	 * @return 菜单树列表
	 */
	public List<MenuTree> buildTreeOptimized(List<SysMenu> menus, String root) {
		if (menus == null) {
			return new ArrayList<>();
		}

		// 转换为 MenuTree 对象
		List<MenuTree> treeNodes = menus.stream()
			.map(menu -> {
				MenuTree node = new MenuTree();
				node.setType(menu.getType());
				node.setId(menu.getId());
				node.setParentId(menu.getParentId());
				node.setName(menu.getName());
				node.setPath(menu.getPath());
				node.setPermission(menu.getPermission());
				node.setLabel(menu.getName());
				node.setComponent(menu.getComponent());
				node.setIcon(menu.getIcon());
				node.setSort(menu.getSort());
				node.setKeepAlive(menu.getKeepAlive());
				return node;
			})
			.collect(Collectors.toList());

		// 使用优化的构建方法
		return buildOptimized(treeNodes, root);
	}
}
