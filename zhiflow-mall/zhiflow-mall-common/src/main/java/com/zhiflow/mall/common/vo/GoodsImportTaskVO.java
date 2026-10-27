package com.zhiflow.mall.common.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 导入任务对外形状：既用于「导入中」轮询进度，也用于结果页与导入记录列表。
 * 不直接吐 {@code GoodsImportTask} 实体，是为了不让 rowsJson / failJson 这两坨大字段进响应体。
 */
@Data
public class GoodsImportTaskVO implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "任务ID")
	private String id;

	@Schema(description = "状态：0草稿 1导入中 2部分成功 3全部成功 4已终止 5执行异常")
	private String status;

	@Schema(description = "模板类型：add / update")
	private String templateType;

	@Schema(description = "文件名")
	private String fileName;

	@Schema(description = "类目展示名")
	private String categoryName;

	@Schema(description = "操作人")
	private String operatorName;

	@Schema(description = "总行数")
	private Integer totalCount;

	@Schema(description = "已处理行数")
	private Integer processedCount;

	@Schema(description = "成功行数")
	private Integer successCount;

	@Schema(description = "失败行数")
	private Integer failCount;

	@Schema(description = "进度百分比 0~100，由已处理/总数换算，服务端算好省得三端各算一遍")
	private Integer percent;

	@Schema(description = "整批异常摘要")
	private String errorMsg;

	@Schema(description = "提交时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime createTime;

	@Schema(description = "开始执行时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime startTime;

	@Schema(description = "结束时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime finishTime;

	@Schema(description = "耗时毫秒")
	private Long durationMs;

	@Schema(description = "失败明细，仅任务详情接口返回；列表接口为空")
	private List<GoodsImportIssueVO> failures = new ArrayList<>();

	@Schema(description = "失败行的原始单元格值（行号 → 字段名 → 文本），供结果页「编辑修正」回填")
	private Map<Integer, Map<String, String>> failRowValues;
}
