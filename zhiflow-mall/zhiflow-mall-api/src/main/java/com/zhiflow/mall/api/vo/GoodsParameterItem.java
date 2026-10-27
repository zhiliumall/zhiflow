package com.zhiflow.mall.api.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 商详页参数区的一行：参数名 + 展示值（多选参数已用「、」拼好）。
 */
@Schema(description = "商品参数")
public record GoodsParameterItem(@Schema(description = "参数名") String name,
		@Schema(description = "参数值") String value) {
}
