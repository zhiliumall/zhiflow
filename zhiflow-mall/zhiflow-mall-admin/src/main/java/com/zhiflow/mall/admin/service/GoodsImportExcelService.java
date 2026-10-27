package com.zhiflow.mall.admin.service;

import com.zhiflow.mall.common.dto.GoodsImportRowDTO;
import com.zhiflow.mall.common.vo.GoodsImportIssueVO;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 商品批量导入的 Excel 读写。列定义只来自
 * {@link com.zhiflow.mall.common.enums.GoodsImportColumn}，本类不自带任何字段清单。
 */
public interface GoodsImportExcelService {

	/**
	 * 生成模板文件字节流。
	 *
	 * @param templateType add 全量新增 / update 增量更新
	 */
	byte[] buildTemplate(String templateType);

	/**
	 * 模板文件名（含 .xlsx），前端下载时用它做 Content-Disposition。
	 */
	String templateFileName(String templateType);

	/**
	 * 解析上传文件为逐行原始文本。只做结构性检查（表头缺列、行数超限），
	 * 字段级校验交给 {@link #validate}。
	 */
	List<GoodsImportRowDTO> parse(InputStream in, String templateType);

	/**
	 * 纯字段级校验：必填、长度、数字、选项、条件必填、文件内编码重复。
	 * 不查库——「编码在库里已存在」由调用方补，那需要 DB。
	 */
	List<GoodsImportIssueVO> validate(List<GoodsImportRowDTO> rows, String templateType);

	/**
	 * 生成「失败数据」下载文件：行号 + 失败原因 + 原始各列，修正后可直接重新上传。
	 */
	byte[] buildFailWorkbook(String templateType, List<GoodsImportIssueVO> issues,
							 Map<Integer, Map<String, String>> rowValues);

	/**
	 * 生成「现有商品导出」文件：表头与**增量更新模板**完全一致，改完可直接原样传回去导入。
	 *
	 * @param rows 每行一个 SKU，key 用列的 field 名；末尾若干 {@code 参考·} 开头的 key
	 *             会写成只读参考列——{@link com.zhiflow.mall.common.enums.GoodsImportColumn#ofHeader}
	 *             认不出这些表头，回传时会被解析直接忽略，所以运营删不删都不影响导入
	 */
	byte[] buildExportWorkbook(List<Map<String, String>> rows);

	/** 单元格能装下的最大字符数（Excel 硬上限），超出的富文本列由导出方转空 */
	int cellTextLimit();
}
