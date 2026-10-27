package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 结果页「编辑修正」后重新导入所选行。
 */
@Data
public class GoodsImportRetryDTO implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "修正后的行，行号沿用原表行号，仅作展示与定位")
	private List<GoodsImportRowDTO> rows;
}
