package com.zhiflow.mall.admin.service;

import com.zhiflow.mall.common.dto.ImpactVO;

/**
 * 删除影响面计数。
 *
 * <p>供各实体删除接口的确认弹窗预览「删了会连带影响什么」。**全部只读**，不得在此新增写方法。
 */
public interface EntityImpactService {

	/** 商品分类：直接子分类 / 分类下商品 */
	ImpactVO goodsCategoryImpact(String categoryId);
}
