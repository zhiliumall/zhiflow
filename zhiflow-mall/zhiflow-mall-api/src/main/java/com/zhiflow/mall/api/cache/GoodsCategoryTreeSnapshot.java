/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zhiflow.common.core.entity.TreeNode;
import com.zhiflow.mall.common.entity.GoodsCategoryTree;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 分类树的一个节点，存在 {@code mall:goods_category#1800::tree} 里（值是根节点列表）。
 *
 * <p>不直接缓存接口返回的 hutool {@link JSONObject}：缓存序列化器的白名单不放行 {@code cn.hutool.}，
 * 读回时会被拒，缓存永远不命中（而且不报错）。快照读出后先还原成 {@link GoodsCategoryTree}，
 * 再走与查库时完全相同的 {@code JSONUtil.parseObj} 转换，所以接口输出与 ETag 都不变。
 */
@Data
public class GoodsCategoryTreeSnapshot implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private String id;
	private String parentId;
	private Integer sort;
	private String enable;
	private String name;
	private String description;
	private String picUrl;
	private String mobilePicUrl;
	private String page;
	private List<String> supportPlatforms;
	private String mobileAdImageUrl;
	private String pcBigImageUrl;
	private List<GoodsCategoryTreeSnapshot> children;

	public static List<GoodsCategoryTreeSnapshot> of(List<GoodsCategoryTree> roots) {
		List<GoodsCategoryTreeSnapshot> nodes = new ArrayList<>(roots.size());
		roots.forEach(root -> nodes.add(of(root)));
		return nodes;
	}

	private static GoodsCategoryTreeSnapshot of(GoodsCategoryTree tree) {
		GoodsCategoryTreeSnapshot node = new GoodsCategoryTreeSnapshot();
		node.setId(tree.getId());
		node.setParentId(tree.getParentId());
		node.setSort(tree.getSort());
		node.setEnable(tree.getEnable());
		node.setName(tree.getName());
		node.setDescription(tree.getDescription());
		node.setPicUrl(tree.getPicUrl());
		node.setMobilePicUrl(tree.getMobilePicUrl());
		node.setPage(tree.getPage());
		node.setSupportPlatforms(tree.getSupportPlatforms() == null ? null
				: new ArrayList<>(Arrays.asList(tree.getSupportPlatforms())));
		node.setMobileAdImageUrl(tree.getMobileAdImageUrl());
		node.setPcBigImageUrl(tree.getPcBigImageUrl());
		List<GoodsCategoryTreeSnapshot> kids = new ArrayList<>();
		if (tree.getChildren() != null) {
			for (TreeNode child : tree.getChildren()) {
				kids.add(of((GoodsCategoryTree) child));
			}
		}
		node.setChildren(kids);
		return node;
	}

	/** 还原成接口原来的返回值：每个根节点一个 hutool JSONObject（子节点嵌在 children 里）。 */
	public static List<JSONObject> toJson(List<GoodsCategoryTreeSnapshot> roots) {
		List<JSONObject> json = new ArrayList<>(roots.size());
		roots.forEach(root -> json.add(JSONUtil.parseObj(root.toTree())));
		return json;
	}

	GoodsCategoryTree toTree() {
		GoodsCategoryTree tree = new GoodsCategoryTree();
		tree.setId(id);
		tree.setParentId(parentId);
		tree.setSort(sort);
		tree.setEnable(enable);
		tree.setName(name);
		tree.setDescription(description);
		tree.setPicUrl(picUrl);
		tree.setMobilePicUrl(mobilePicUrl);
		tree.setPage(page);
		tree.setSupportPlatforms(supportPlatforms == null ? null : supportPlatforms.toArray(new String[0]));
		tree.setMobileAdImageUrl(mobileAdImageUrl);
		tree.setPcBigImageUrl(pcBigImageUrl);
		if (children != null) {
			children.forEach(child -> tree.addChildren(child.toTree()));
		}
		return tree;
	}
}
