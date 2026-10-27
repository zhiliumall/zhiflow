package com.zhiflow.mall.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 上传预校验结果。对应设计稿「上传步骤」里文件行下方的
 * 「✓ 校验通过 N 条 / 异常 M 条 ▼」与展开的异常明细。
 */
@Data
public class GoodsImportPreviewVO implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "草稿任务ID，点「开始导入」时回传")
	private String taskId;

	@Schema(description = "文件名")
	private String fileName;

	@Schema(description = "文件字节数")
	private Long fileSize;

	@Schema(description = "数据总行数")
	private Integer total;

	@Schema(description = "校验通过行数")
	private Integer validCount;

	@Schema(description = "异常行数（按行去重，一行多个问题只计一次）")
	private Integer invalidCount;

	@Schema(description = "异常明细，逐条对应一个字段问题")
	private List<GoodsImportIssueVO> issues = new ArrayList<>();
}
