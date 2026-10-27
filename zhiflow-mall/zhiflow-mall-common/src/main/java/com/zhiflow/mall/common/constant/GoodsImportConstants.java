package com.zhiflow.mall.common.constant;

/**
 * 商品批量导入的状态机与口径常量。前后端共用同一套字符串码，前端不得自造第二套。
 */
public interface GoodsImportConstants {

	// ===== goods_import_task.status =====

	/** 已预校验、等待运营点「开始导入」的草稿任务。不出现在导入记录列表里 */
	String STATUS_DRAFT = "0";
	/** 导入中 */
	String STATUS_RUNNING = "1";
	/** 部分成功（有失败行） */
	String STATUS_PARTIAL = "2";
	/** 全部成功 */
	String STATUS_SUCCESS = "3";
	/** 已终止（运营主动终止，已写入的行不回滚） */
	String STATUS_ABORTED = "4";
	/** 执行异常（整批未能跑完，非逐行失败） */
	String STATUS_FAILED = "5";

	// ===== 重复编码策略 =====

	/** 跳过该条（默认）：编码已存在的行直接判失败，不动库里的老商品 */
	String DUP_SKIP = "skip";
	/** 覆盖更新：编码已存在则按增量更新语义覆盖 */
	String DUP_OVERWRITE = "overwrite";
	/** 终止导入：遇到第一条重复编码即停，剩余行不再处理 */
	String DUP_ABORT = "abort";

	// ===== 失败原因归类（前端筛选 chips 用）=====

	/** 重复编码 */
	String ISSUE_DUPLICATE = "duplicate";
	/** 缺必填 */
	String ISSUE_REQUIRED = "required";
	/** 格式错误（数字/选项/长度不合法） */
	String ISSUE_FORMAT = "format";
	/** 其他（关联数据找不到、落库异常等） */
	String ISSUE_OTHER = "other";

	// ===== 上传限制 =====

	/** 单个文件大小上限 10MB */
	long MAX_FILE_SIZE = 10L * 1024 * 1024;
	/** 单次最多导入行数 */
	int MAX_ROWS = 500;
	/** 导入记录保留天数 */
	int RECORD_RETAIN_DAYS = 90;

	/** 图片/渠道/资质等多值列的分隔符（英文逗号在解析前会先归一成竖线） */
	String MULTI_VALUE_SEPARATOR = "|";
}
