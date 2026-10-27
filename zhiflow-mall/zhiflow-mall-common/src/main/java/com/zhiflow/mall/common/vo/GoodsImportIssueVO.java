package com.zhiflow.mall.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一条行级问题。预校验阶段（上传后）与执行阶段（落库失败）共用同一形状，
 * 前端的失败原因筛选 chips 直接按 {@link #type} 分组计数。
 */
@Data
public class GoodsImportIssueVO implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "Excel 行号")
	private Integer rowNo;

	@Schema(description = "商品名称，用于结果页失败表展示")
	private String name;

	@Schema(description = "出问题的列名（表头文本），整行级问题为空")
	private String header;

	@Schema(description = "失败原因文案")
	private String reason;

	@Schema(description = "归类：duplicate / required / format / other")
	private String type;

	public static GoodsImportIssueVO of(Integer rowNo, String name, String header, String type, String reason) {
		GoodsImportIssueVO vo = new GoodsImportIssueVO();
		vo.setRowNo(rowNo);
		vo.setName(name);
		vo.setHeader(header);
		vo.setType(type);
		vo.setReason(reason);
		return vo;
	}
}
