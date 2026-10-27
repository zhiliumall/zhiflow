package com.zhiflow.mall.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.dto.GoodsImportRowDTO;
import com.zhiflow.mall.common.dto.GoodsImportStartDTO;
import com.zhiflow.mall.common.entity.GoodsImportTask;
import com.zhiflow.mall.common.vo.GoodsImportPreviewVO;
import com.zhiflow.mall.common.vo.GoodsImportTaskVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 商品批量导入任务。生命周期：
 * 上传预校验（草稿）→ 开始导入（异步跑）→ 轮询进度 →（可终止）→ 结果 / 失败行修正后重导。
 */
public interface GoodsImportTaskService extends IService<GoodsImportTask> {

	/** 上传并预校验，落一条草稿任务；返回结果直接喂给上传步骤的「校验通过 N 条 / 异常 M 条」 */
	GoodsImportPreviewVO precheck(MultipartFile file, GoodsImportTask form);

	/** 开始导入：把草稿任务转成运行态并异步执行 */
	GoodsImportTaskVO start(String taskId, GoodsImportStartDTO options);

	/** 进度 / 结果详情。失败明细只在这个接口返回 */
	GoodsImportTaskVO detail(String taskId);

	/** 请求终止：只置信号位，由执行线程在行间隙自己停下来，已写入的行不回滚 */
	void abort(String taskId);

	/** 导入记录分页（不含草稿任务） */
	IPage<GoodsImportTaskVO> pageRecords(IPage<GoodsImportTask> page, String status, Integer days);

	/** 商品列表工具栏角标用：当前租户是否有导入中的任务 */
	List<GoodsImportTaskVO> listRunning();

	/** 失败数据导出 */
	byte[] exportFail(String taskId);

	/** 失败行修正后重导：按原任务的类目/店铺/策略新建一个任务并立即开跑 */
	GoodsImportTaskVO retry(String taskId, List<GoodsImportRowDTO> rows);
}
