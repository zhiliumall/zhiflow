package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析后的一行导入数据。
 *
 * <p>{@link #values} 的 key 是 {@code GoodsImportColumn.getField()}（如 {@code name}、
 * {@code salesPrice}），value 一律是**未经类型转换的原始字符串**——转换与校验发生在
 * 落库前一刻，这样「失败行修正后重导」时前端回传的仍是同一形状，不需要第二套契约。
 */
@Data
public class GoodsImportRowDTO implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	/** Excel 里的物理行号（1 基，含表头行），失败明细直接展示这个数字，方便运营回表格定位 */
	@Schema(description = "Excel 行号")
	private Integer rowNo;

	@Schema(description = "字段名 → 原始单元格文本")
	private Map<String, String> values = new LinkedHashMap<>();

	public String get(String field) {
		return values == null ? null : values.get(field);
	}
}
