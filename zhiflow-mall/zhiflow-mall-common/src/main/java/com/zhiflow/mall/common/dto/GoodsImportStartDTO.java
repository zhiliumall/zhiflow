package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 「开始导入」时提交的策略与高级设置。对应设计稿上传步骤的重复策略分段单选 + 高级设置三个复选框。
 */
@Data
public class GoodsImportStartDTO implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "重复编码策略：skip 跳过该条（默认）/ overwrite 覆盖更新 / abort 终止导入")
	private String dupStrategy;

	@Schema(description = "忽略异常行，仅导入通过的行：1是（默认）0否")
	private String skipInvalid;

	@Schema(description = "导入后一律下架（忽略表格里的上架状态）：1是 0否")
	private String forceOffShelf;

	@Schema(description = "库存一律置0（忽略表格里的库存）：1是 0否")
	private String zeroStock;

	@Schema(description = "覆盖更新时一并同步价格：1是 0否（默认否，防止误改在售价格）")
	private String syncPrice;
}
