package com.zhiflow.mall.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.zhiflow.common.core.entity.base.TenantEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 商品批量导入任务。
 *
 * <p>一次上传对应一行：预校验通过即落 {@code status='0'} 草稿，运营点「开始导入」后转为运行态，
 * 由 {@code GoodsImportRunner} 异步逐行写库并回写进度。
 *
 * <p><b>为什么把解析后的行存在 {@code rowsJson} 而不是留住原始文件</b>：单次上限 500 行、
 * 单文件 10MB（{@link com.zhiflow.mall.common.constant.GoodsImportConstants}），
 * 解析结果远小于原文件；存行还能让「失败行修正后重导」直接改 JSON，不必让运营重新传一遍。
 * 代价是原始文件不落盘，导入记录里的文件名只作展示、不提供原文件下载。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goods_import_task")
@Schema(description = "商品批量导入任务")
public class GoodsImportTask extends TenantEntity {

	@Schema(description = "PK")
	@TableId(type = IdType.ASSIGN_ID)
	private String id;

	@Schema(description = "店铺ID")
	private String shopId;

	@Schema(description = "模板类型：add 全量新增 / update 增量更新")
	private String templateType;

	@Schema(description = "上传文件名")
	private String fileName;

	@Schema(description = "上传文件字节数")
	private Long fileSize;

	@Schema(description = "商城一级分类ID")
	private String categoryFirst;

	@Schema(description = "商城二级分类ID")
	private String categorySecond;

	@Schema(description = "商城三级分类ID")
	private String categoryThird;

	@Schema(description = "类目展示名，形如「食品饮料 / 休闲零食」，仅用于导入记录展示")
	private String categoryName;

	@Schema(description = "重复编码策略：skip / overwrite / abort")
	private String dupStrategy;

	@Schema(description = "忽略异常行只导入通过行：1是 0否")
	private String skipInvalid;

	@Schema(description = "高级设置-导入后一律下架：1是 0否")
	private String forceOffShelf;

	@Schema(description = "高级设置-库存置0：1是 0否")
	private String zeroStock;

	@Schema(description = "高级设置-覆盖更新时一并同步价格：1是 0否")
	private String syncPrice;

	@Schema(description = "文件总行数（不含表头与说明行）")
	private Integer totalCount;

	@Schema(description = "已处理行数")
	private Integer processedCount;

	@Schema(description = "成功行数")
	private Integer successCount;

	@Schema(description = "失败行数")
	private Integer failCount;

	@Schema(description = "状态：0草稿 1导入中 2部分成功 3全部成功 4已终止 5执行异常")
	private String status;

	@Schema(description = "整批失败时的异常摘要")
	private String errorMsg;

	@Schema(description = "终止信号：1请求终止")
	private String abortFlag;

	@Schema(description = "操作人昵称")
	private String operatorName;

	@Schema(description = "解析后的行数据 JSON 数组")
	@TableField(select = false)
	private String rowsJson;

	@Schema(description = "逐行失败明细 JSON 数组")
	@TableField(select = false)
	private String failJson;

	@Schema(description = "重试来源任务ID")
	private String sourceTaskId;

	@Schema(description = "开始执行时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime startTime;

	@Schema(description = "结束时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime finishTime;

	@Schema(description = "耗时毫秒")
	private Long durationMs;
}
