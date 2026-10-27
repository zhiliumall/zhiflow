package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.admin.config.GoodsImportAsyncConfig;
import com.zhiflow.mall.admin.mapper.GoodsImportTaskMapper;
import com.zhiflow.mall.admin.service.GoodsBrandService;
import com.zhiflow.mall.admin.service.GoodsImportContext;
import com.zhiflow.mall.admin.service.GoodsImportRowWriter;
import com.zhiflow.mall.admin.service.GoodsImportRunner;
import com.zhiflow.mall.admin.service.GoodsSpecService;
import com.zhiflow.mall.admin.service.GoodsSpecValueService;
import com.zhiflow.mall.common.constant.GoodsImportConstants;
import com.zhiflow.mall.common.dto.GoodsImportRowDTO;
import com.zhiflow.mall.common.entity.GoodsBrand;
import com.zhiflow.mall.common.entity.GoodsImportTask;
import com.zhiflow.mall.common.entity.GoodsSpec;
import com.zhiflow.mall.common.entity.GoodsSpecValue;
import com.zhiflow.mall.common.enums.GoodsImportColumn;
import com.zhiflow.mall.common.utils.GoodsImportRowGroups;
import com.zhiflow.mall.common.vo.GoodsImportIssueVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 逐行跑批。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>每行独立事务（{@link GoodsImportRowWriter}），一行炸不影响前面已写入的行；</li>
 *   <li>进度每 {@value #FLUSH_EVERY} 行落一次库，前端轮询才能看到条子在动；</li>
 *   <li>终止信号在行间隙检查，已写入的行**不回滚**——回滚一批已经上架的商品比停不下来更危险。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsImportRunnerImpl implements GoodsImportRunner {

	/** 每处理多少行落一次进度 / 检查一次终止信号 */
	private static final int FLUSH_EVERY = 5;

	private final GoodsImportTaskMapper taskMapper;
	private final GoodsImportRowWriter rowWriter;
	private final GoodsBrandService goodsBrandService;
	private final GoodsSpecService goodsSpecService;
	private final GoodsSpecValueService goodsSpecValueService;
	private final ObjectMapper objectMapper;

	@Override
	@Async(GoodsImportAsyncConfig.EXECUTOR)
	public void run(String taskId, String tenantId) {
		TenantContextHolder.setTenantId(tenantId);
		try {
			execute(taskId);
		} catch (Exception e) {
			log.error("商品批量导入执行异常, taskId={}", taskId, e);
			markFailed(taskId, e);
		} finally {
			TenantContextHolder.clear();
		}
	}

	private void execute(String taskId) {
		GoodsImportTask task = taskMapper.selectById(taskId);
		if (task == null) {
			log.warn("导入任务不存在, taskId={}", taskId);
			return;
		}
		List<GoodsImportRowDTO> rows = readRows(taskId);
		// 预校验阶段就判定为异常的行：勾了「忽略异常行」时它们不进执行环节，直接记为失败
		Set<Integer> skipRows = new HashSet<>();
		List<GoodsImportIssueVO> failures = new ArrayList<>(readIssues(taskId));
		failures.forEach(issue -> skipRows.add(issue.getRowNo()));

		GoodsImportContext context = buildContext(task.getShopId());
		long startedAt = System.currentTimeMillis();

		int processed = 0;
		int success = 0;
		boolean aborted = false;
		// 按商品编码分组：一个多规格商品的 N 行必须整体成败，否则第 2 行起会撞唯一键
		for (List<GoodsImportRowDTO> group : GoodsImportRowGroups.groupList(rows)) {
			if (isAbortRequested(taskId)) {
				aborted = true;
				break;
			}
			processed += group.size();
			// 预校验就判失败的行不再尝试落库；组内任一行有问题即整组跳过——
			// 多规格商品少一个规格就是残品，不如整组留给运营修完重导
			boolean skipped = group.stream().anyMatch(row -> skipRows.contains(row.getRowNo()));
			if (!skipped) {
				try {
					rowWriter.write(task, group, context);
					success += group.size();
				} catch (DuplicateKeyException e) {
					// 唯一键是 (tenant_id, spu_code) 且不看 del_flag：软删的老商品仍占着编码，
					// 前面按 del_flag='0' 查不到它，只有真正 INSERT 时才炸出来
					log.warn("导入编码冲突, taskId={}, row={}", taskId, group.get(0).getRowNo(), e);
					failGroup(failures, group, GoodsImportConstants.ISSUE_DUPLICATE,
							"商品编码已存在（可能被已删除的商品占用）");
					if (GoodsImportConstants.DUP_ABORT.equals(task.getDupStrategy())) {
						aborted = true;
						break;
					}
				} catch (BizException e) {
					failGroup(failures, group, classify(e.getMessage()), e.getMessage());
					if (GoodsImportConstants.DUP_ABORT.equals(task.getDupStrategy())
							&& StringUtils.contains(e.getMessage(), "商品编码已存在")) {
						aborted = true;
						break;
					}
				} catch (Exception e) {
					log.error("导入落库失败, taskId={}, row={}", taskId, group.get(0).getRowNo(), e);
					failGroup(failures, group, GoodsImportConstants.ISSUE_OTHER, "写入失败，请稍后重试");
				}
			}
			flushProgress(taskId, processed, success, failures.size());
		}

		finish(taskId, rows, failures, processed, success, aborted, startedAt);
	}

	// ===================== 收尾 =====================

	private void finish(String taskId, List<GoodsImportRowDTO> rows, List<GoodsImportIssueVO> failures,
						int processed, int success, boolean aborted, long startedAt) {
		Set<Integer> failedRows = failures.stream().map(GoodsImportIssueVO::getRowNo).collect(Collectors.toSet());
		GoodsImportTask update = new GoodsImportTask();
		update.setId(taskId);
		update.setProcessedCount(processed);
		update.setSuccessCount(success);
		update.setFailCount(failedRows.size());
		update.setStatus(aborted ? GoodsImportConstants.STATUS_ABORTED
				: (failedRows.isEmpty() ? GoodsImportConstants.STATUS_SUCCESS : GoodsImportConstants.STATUS_PARTIAL));
		update.setFinishTime(LocalDateTime.now());
		update.setDurationMs(System.currentTimeMillis() - startedAt);
		update.setFailJson(writeJson(failures));
		// 只留失败行的原始值，成功行的没人再看，留着白占几百 KB
		update.setRowsJson(writeJson(rows.stream()
				.filter(row -> failedRows.contains(row.getRowNo()))
				.toList()));
		taskMapper.updateById(update);
	}

	private void markFailed(String taskId, Exception e) {
		try {
			GoodsImportTask update = new GoodsImportTask();
			update.setId(taskId);
			update.setStatus(GoodsImportConstants.STATUS_FAILED);
			update.setErrorMsg(StringUtils.abbreviate(String.valueOf(e.getMessage()), 900));
			update.setFinishTime(LocalDateTime.now());
			taskMapper.updateById(update);
		} catch (Exception ignore) {
			log.error("回写导入任务失败状态也失败了, taskId={}", taskId, ignore);
		}
	}

	private void flushProgress(String taskId, int processed, int success, int failCount) {
		GoodsImportTask update = new GoodsImportTask();
		update.setId(taskId);
		update.setProcessedCount(processed);
		update.setSuccessCount(success);
		update.setFailCount(failCount);
		taskMapper.updateById(update);
	}

	private boolean isAbortRequested(String taskId) {
		GoodsImportTask current = taskMapper.selectById(taskId);
		return current != null && "1".equals(current.getAbortFlag());
	}

	// ===================== 装配 =====================

	private GoodsImportContext buildContext(String shopId) {
		GoodsImportContext context = new GoodsImportContext();
		List<GoodsBrand> brands = goodsBrandService.list(Wrappers.<GoodsBrand>lambdaQuery()
				.select(GoodsBrand::getId, GoodsBrand::getBrandName));
		context.setBrandIdByName(brands.stream()
				.filter(brand -> StringUtils.isNotBlank(brand.getBrandName()))
				.collect(Collectors.toMap(GoodsBrand::getBrandName, GoodsBrand::getId, (a, b) -> a)));

		// 规格字典：goods_spec 是租户级的规格项（「规格」「包装」），goods_spec_value 是其下的可选值。
		// 导入只匹配不新建——从表格里自动建字典，一个错别字就会让「40mg*90片」和「40mg×90片」并存。
		List<GoodsSpec> specs = goodsSpecService.list(Wrappers.<GoodsSpec>lambdaQuery()
				.select(GoodsSpec::getId, GoodsSpec::getName));
		context.setSpecIdByName(specs.stream()
				.filter(spec -> StringUtils.isNotBlank(spec.getName()))
				.collect(Collectors.toMap(GoodsSpec::getName, GoodsSpec::getId, (a, b) -> a)));

		List<GoodsSpecValue> specValues = goodsSpecValueService.list(Wrappers.<GoodsSpecValue>lambdaQuery()
				.select(GoodsSpecValue::getId, GoodsSpecValue::getSpecId, GoodsSpecValue::getName));
		Map<String, String> valueMap = new HashMap<>();
		for (GoodsSpecValue value : specValues) {
			if (StringUtils.isNotBlank(value.getName()) && StringUtils.isNotBlank(value.getSpecId())) {
				valueMap.putIfAbsent(GoodsImportContext.specValueKey(value.getSpecId(), value.getName()),
						value.getId());
			}
		}
		context.setSpecValueIdByKey(valueMap);
		return context;
	}

	/**
	 * 整组判失败：组内每行都记一条。不只记组头，是为了让「失败 N 条」与「已处理 N 条」口径一致，
	 * 也让运营在结果页勾选重导时能把这个商品的所有规格行一起选上。
	 */
	private void failGroup(List<GoodsImportIssueVO> failures, List<GoodsImportRowDTO> group,
						   String type, String reason) {
		String name = group.get(0).get(GoodsImportColumn.NAME.getField());
		for (GoodsImportRowDTO row : group) {
			failures.add(GoodsImportIssueVO.of(row.getRowNo(), name, null, type, reason));
		}
	}

	/** 把执行期异常的文案粗分到前端筛选 chips 的四类里 */
	private String classify(String message) {
		if (StringUtils.contains(message, "编码已存在") || StringUtils.contains(message, "编码不存在")) {
			return GoodsImportConstants.ISSUE_DUPLICATE;
		}
		if (StringUtils.contains(message, "必填")) {
			return GoodsImportConstants.ISSUE_REQUIRED;
		}
		return GoodsImportConstants.ISSUE_OTHER;
	}

	// ===================== JSON =====================

	private List<GoodsImportRowDTO> readRows(String taskId) {
		String json = taskMapper.selectRowsJson(taskId);
		if (StringUtils.isBlank(json)) {
			return List.of();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<List<GoodsImportRowDTO>>() {
			});
		} catch (Exception e) {
			throw new BizException("任务数据已损坏，请重新上传文件");
		}
	}

	private List<GoodsImportIssueVO> readIssues(String taskId) {
		String json = taskMapper.selectFailJson(taskId);
		if (StringUtils.isBlank(json)) {
			return List.of();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<List<GoodsImportIssueVO>>() {
			});
		} catch (Exception e) {
			log.warn("读取预校验异常明细失败, taskId={}", taskId, e);
			return List.of();
		}
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception e) {
			log.error("导入任务 JSON 序列化失败", e);
			return "[]";
		}
	}

	/** 失败行的原始值，按行号索引，供结果页「编辑修正」回填与失败数据导出 */
	public static Map<Integer, Map<String, String>> indexByRow(List<GoodsImportRowDTO> rows) {
		Map<Integer, Map<String, String>> map = new LinkedHashMap<>();
		for (GoodsImportRowDTO row : rows) {
			map.put(row.getRowNo(), row.getValues() == null ? new HashMap<>(0) : row.getValues());
		}
		return map;
	}
}
