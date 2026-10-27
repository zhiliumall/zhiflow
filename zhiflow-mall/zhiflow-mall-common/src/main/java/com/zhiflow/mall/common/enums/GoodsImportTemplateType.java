package com.zhiflow.mall.common.enums;

/**
 * 批量导入模板类型。
 *
 * <p>两种模板列集合相同，差别在语义：
 * ADD 整行落库新建商品；UPDATE 按商品编码匹配已有商品，空单元格跳过不覆盖。
 */
public enum GoodsImportTemplateType {

	/** 全量新增模板 */
	ADD("add", "商品新增模板"),

	/** 增量更新模板 */
	UPDATE("update", "商品更新模板");

	private final String code;
	private final String fileLabel;

	GoodsImportTemplateType(String code, String fileLabel) {
		this.code = code;
		this.fileLabel = fileLabel;
	}

	public String getCode() {
		return code;
	}

	public String getFileLabel() {
		return fileLabel;
	}

	/** 未知或空值一律回落到 ADD，避免非法入参把导出接口打成 500 */
	public static GoodsImportTemplateType of(String code) {
		for (GoodsImportTemplateType type : values()) {
			if (type.code.equalsIgnoreCase(code)) {
				return type;
			}
		}
		return ADD;
	}
}
