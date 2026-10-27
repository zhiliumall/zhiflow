package com.zhiflow.mall.admin.service;

import com.zhiflow.mall.common.entity.GoodsSpu;

import java.util.List;

/**
 * 现有商品导出成「增量更新模板」格式的 Excel：导出 → 在表格里改 → 原样传回批量导入。
 *
 * <p>与批量导入共用同一套列定义（{@code GoodsImportColumn}），所以导出文件天然能被导入解析，
 * 不存在「导出一套表头、导入另一套表头」的对不上问题。
 */
public interface GoodsExportService {

	/**
	 * @param query 与商品列表 {@code /goodsspu/page} 完全相同的查询条件对象，
	 *              保证「列表里筛出什么就导出什么」
	 * @param ids   勾选导出的商品 ID；非空时忽略 {@code query}
	 */
	byte[] export(GoodsSpu query, List<String> ids);
}
