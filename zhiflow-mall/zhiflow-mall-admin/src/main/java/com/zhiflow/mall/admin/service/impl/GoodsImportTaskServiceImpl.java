package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.common.security.util.SecurityUtils;
import com.zhiflow.mall.admin.mapper.GoodsImportTaskMapper;
import com.zhiflow.mall.admin.service.GoodsImportExcelService;
import com.zhiflow.mall.admin.service.GoodsImportRunner;
import com.zhiflow.mall.admin.service.GoodsImportTaskService;
import com.zhiflow.mall.admin.service.GoodsSpuService;
import com.zhiflow.mall.common.constant.GoodsImportConstants;
import com.zhiflow.mall.common.dto.GoodsImportRowDTO;
import com.zhiflow.mall.common.dto.GoodsImportStartDTO;
import com.zhiflow.mall.common.entity.GoodsImportTask;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.enums.GoodsImportColumn;
import com.zhiflow.mall.common.enums.GoodsImportTemplateType;
import com.zhiflow.mall.common.vo.GoodsImportIssueVO;
import com.zhiflow.mall.common.vo.GoodsImportPreviewVO;
import com.zhiflow.mall.common.vo.GoodsImportTaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 导入任务编排。真正的行级写库在 {@link com.zhiflow.mall.admin.service.GoodsImportRowWriter}，
 * 跑批循环在 {@link GoodsImportRunner}，本类只管状态流转与读写 JSON 载荷。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsImportTaskServiceImpl extends ServiceImpl<GoodsImportTaskMapper, GoodsImportTask>
		implements GoodsImportTaskService {

	private final GoodsImportExcelService excelService;
	private final GoodsImportRunner runner;
	private final GoodsSpuService goodsSpuService;
	private final ObjectMapper objectMapper;

	@Override
	public GoodsImportPreviewVO precheck(MultipartFile file, GoodsImportTask form) {
		if (file == null || file.isEmpty()) {
			throw new BizException("请选择要上传的数据文件");
		}
		String originalName = StringUtils.defaultString(file.getOriginalFilename());
		if (!StringUtils.endsWithAny(originalName.toLowerCase(), ".xlsx", ".xls")) {
			throw new BizException("只支持 .xlsx / .xls 文件");
		}
		if (file.getSize() > GoodsImportConstants.MAX_FILE_SIZE) {
			throw new BizException("文件不能超过 10MB");
		}
		// 单店铺：表单没传就落默认店铺
		String shopId = StringUtils.defaultIfBlank(form.getShopId(), CommonConstants.DEFAULT_SHOP_ID);
		String templateType = GoodsImportTemplateType.of(form.getTemplateType()).getCode();
		if (GoodsImportTemplateType.ADD.getCode().equals(templateType)
				&& StringUtils.isBlank(form.getCategoryFirst())) {
			throw new BizException("请先选择商品类目");
		}

		List<GoodsImportRowDTO> rows;
		try (InputStream in = file.getInputStream()) {
			rows = excelService.parse(in, templateType);
		} catch (BizException e) {
			throw e;
		} catch (Exception e) {
			log.error("读取导入文件失败, file={}", originalName, e);
			throw new BizException("文件读取失败，请重新上传");
		}

		List<GoodsImportIssueVO> issues = new ArrayList<>(excelService.validate(rows, templateType));
		issues.addAll(checkCodeExistence(rows, templateType));

		GoodsImportTask task = new GoodsImportTask();
		task.setShopId(shopId);
		task.setTemplateType(templateType);
		task.setFileName(originalName);
		task.setFileSize(file.getSize());
		task.setCategoryFirst(form.getCategoryFirst());
		task.setCategorySecond(form.getCategorySecond());
		task.setCategoryThird(form.getCategoryThird());
		task.setCategoryName(StringUtils.abbreviate(form.getCategoryName(), 200));
		task.setStatus(GoodsImportConstants.STATUS_DRAFT);
		task.setDupStrategy(GoodsImportConstants.DUP_SKIP);
		task.setSkipInvalid("1");
		task.setForceOffShelf("1");
		task.setZeroStock("0");
		task.setSyncPrice("0");
		task.setTotalCount(rows.size());
		task.setProcessedCount(0);
		task.setSuccessCount(0);
		task.setFailCount(0);
		task.setAbortFlag("0");
		task.setOperatorName(currentUsername());
		task.setRowsJson(writeJson(rows));
		task.setFailJson(writeJson(issues));
		save(task);

		GoodsImportPreviewVO preview = new GoodsImportPreviewVO();
		preview.setTaskId(task.getId());
		preview.setFileName(originalName);
		preview.setFileSize(file.getSize());
		preview.setTotal(rows.size());
		preview.setIssues(issues);
		int invalid = (int) issues.stream().map(GoodsImportIssueVO::getRowNo).distinct().count();
		preview.setInvalidCount(invalid);
		preview.setValidCount(rows.size() - invalid);
		return preview;
	}

	@Override
	public GoodsImportTaskVO start(String taskId, GoodsImportStartDTO options) {
		GoodsImportTask task = getExisting(taskId);
		if (!GoodsImportConstants.STATUS_DRAFT.equals(task.getStatus())) {
			throw new BizException("该任务已提交，请勿重复导入");
		}
		String dupStrategy = StringUtils.defaultIfBlank(options.getDupStrategy(), GoodsImportConstants.DUP_SKIP);
		boolean skipInvalid = !"0".equals(options.getSkipInvalid());

		// 选了「覆盖更新」之后，预校验里那批「编码已存在」就不再是问题了，
		// 得把它们从待跳过清单里摘掉，否则运营明明选了覆盖却一条都进不去
		List<GoodsImportIssueVO> effective = readIssues(taskId).stream()
				.filter(issue -> !isDuplicateWaived(issue, task.getTemplateType(), dupStrategy))
				.toList();
		if (!skipInvalid && !effective.isEmpty()) {
			long rows = effective.stream().map(GoodsImportIssueVO::getRowNo).distinct().count();
			throw new BizException("文件里有 " + rows + " 条异常数据，请修正后重新上传，或勾选「忽略异常行」");
		}

		GoodsImportTask update = new GoodsImportTask();
		update.setId(taskId);
		update.setDupStrategy(dupStrategy);
		update.setSkipInvalid(skipInvalid ? "1" : "0");
		update.setForceOffShelf(flag(options.getForceOffShelf(), true));
		update.setZeroStock(flag(options.getZeroStock(), true));
		update.setSyncPrice(flag(options.getSyncPrice(), false));
		update.setStatus(GoodsImportConstants.STATUS_RUNNING);
		update.setStartTime(LocalDateTime.now());
		update.setAbortFlag("0");
		update.setFailJson(writeJson(effective));
		updateById(update);

		// tenantId 必须在请求线程里取好传进去：执行线程没有请求上下文
		runner.run(taskId, TenantContextHolder.getTenantId());
		return detail(taskId);
	}

	@Override
	public GoodsImportTaskVO detail(String taskId) {
		GoodsImportTask task = getExisting(taskId);
		GoodsImportTaskVO vo = toVO(task);
		List<GoodsImportIssueVO> failures = readIssues(taskId);
		vo.setFailures(failures);
		if (!failures.isEmpty()) {
			vo.setFailRowValues(GoodsImportRunnerImpl.indexByRow(readRows(taskId)));
		}
		return vo;
	}

	@Override
	public void abort(String taskId) {
		GoodsImportTask task = getExisting(taskId);
		if (!GoodsImportConstants.STATUS_RUNNING.equals(task.getStatus())) {
			return;
		}
		GoodsImportTask update = new GoodsImportTask();
		update.setId(taskId);
		update.setAbortFlag("1");
		updateById(update);
	}

	@Override
	public IPage<GoodsImportTaskVO> pageRecords(IPage<GoodsImportTask> page, String status, Integer days) {
		int window = days == null || days <= 0 ? 30 : Math.min(days, GoodsImportConstants.RECORD_RETAIN_DAYS);
		IPage<GoodsImportTask> result = page(page, Wrappers.<GoodsImportTask>lambdaQuery()
				.ne(GoodsImportTask::getStatus, GoodsImportConstants.STATUS_DRAFT)
				.eq(StringUtils.isNotBlank(status), GoodsImportTask::getStatus, status)
				.ge(GoodsImportTask::getCreateTime, LocalDateTime.now().minusDays(window))
				.orderByDesc(GoodsImportTask::getCreateTime));
		return result.convert(this::toVO);
	}

	@Override
	public List<GoodsImportTaskVO> listRunning() {
		return list(Wrappers.<GoodsImportTask>lambdaQuery()
				.eq(GoodsImportTask::getStatus, GoodsImportConstants.STATUS_RUNNING)
				.orderByDesc(GoodsImportTask::getCreateTime))
				.stream().map(this::toVO).toList();
	}

	@Override
	public byte[] exportFail(String taskId) {
		GoodsImportTask task = getExisting(taskId);
		List<GoodsImportIssueVO> failures = readIssues(taskId);
		if (failures.isEmpty()) {
			throw new BizException("该任务没有失败数据");
		}
		return excelService.buildFailWorkbook(task.getTemplateType(), failures,
				GoodsImportRunnerImpl.indexByRow(readRows(taskId)));
	}

	@Override
	public GoodsImportTaskVO retry(String taskId, List<GoodsImportRowDTO> rows) {
		GoodsImportTask source = getExisting(taskId);
		if (rows == null || rows.isEmpty()) {
			throw new BizException("请选择要重新导入的数据");
		}
		rows = withMissingSpecSiblings(taskId, rows);
		if (rows.size() > GoodsImportConstants.MAX_ROWS) {
			throw new BizException("单次最多导入 " + GoodsImportConstants.MAX_ROWS + " 条");
		}
		List<GoodsImportIssueVO> issues = new ArrayList<>(excelService.validate(rows, source.getTemplateType()));
		issues.addAll(checkCodeExistence(rows, source.getTemplateType()));

		GoodsImportTask task = new GoodsImportTask();
		task.setShopId(source.getShopId());
		task.setTemplateType(source.getTemplateType());
		task.setFileName(source.getFileName());
		task.setFileSize(source.getFileSize());
		task.setCategoryFirst(source.getCategoryFirst());
		task.setCategorySecond(source.getCategorySecond());
		task.setCategoryThird(source.getCategoryThird());
		task.setCategoryName(source.getCategoryName());
		task.setStatus(GoodsImportConstants.STATUS_DRAFT);
		task.setDupStrategy(source.getDupStrategy());
		task.setSkipInvalid(source.getSkipInvalid());
		task.setForceOffShelf(source.getForceOffShelf());
		task.setZeroStock(source.getZeroStock());
		task.setSyncPrice(source.getSyncPrice());
		task.setTotalCount(rows.size());
		task.setProcessedCount(0);
		task.setSuccessCount(0);
		task.setFailCount(0);
		task.setAbortFlag("0");
		task.setOperatorName(currentUsername());
		task.setSourceTaskId(taskId);
		task.setRowsJson(writeJson(rows));
		task.setFailJson(writeJson(issues));
		save(task);

		GoodsImportStartDTO options = new GoodsImportStartDTO();
		options.setDupStrategy(source.getDupStrategy());
		options.setSkipInvalid("1");
		options.setForceOffShelf(source.getForceOffShelf());
		options.setZeroStock(source.getZeroStock());
		options.setSyncPrice(source.getSyncPrice());
		return start(task.getId(), options);
	}

	// ===================== 内部 =====================

	/**
	 * 补回同一商品编码下、运营没勾选的那些规格行。
	 *
	 * <p>结果页的失败表是按行展示的，多规格商品的 3 个规格是 3 行，运营很可能只勾了报错的那一行。
	 * 直接按勾选提交会让这个商品被建成「只有 1 个规格」的残品——而它原本有 3 个。
	 * 这里以服务端为准把兄弟行补齐，用的是原任务里保存的原值（未勾选的行本来也没被改过）。
	 */
	private List<GoodsImportRowDTO> withMissingSpecSiblings(String taskId, List<GoodsImportRowDTO> picked) {
		String field = GoodsImportColumn.SPU_CODE.getField();
		Set<String> codes = picked.stream().map(row -> row.get(field))
				.filter(StringUtils::isNotBlank).collect(Collectors.toSet());
		Set<Integer> pickedRowNos = picked.stream().map(GoodsImportRowDTO::getRowNo)
				.filter(Objects::nonNull).collect(Collectors.toSet());

		List<GoodsImportRowDTO> merged = new ArrayList<>(picked);
		for (GoodsImportRowDTO row : readRows(taskId)) {
			String code = row.get(field);
			if (StringUtils.isNotBlank(code) && codes.contains(code)
					&& !pickedRowNos.contains(row.getRowNo())) {
				merged.add(row);
			}
		}
		// 按原表行号排序，组头（SPU 级字段所在行）才会回到第一位
		merged.sort(Comparator.comparing(row -> row.getRowNo() == null ? Integer.MAX_VALUE : row.getRowNo()));
		return merged;
	}

	/**
	 * 编码存在性检查。新增模板里「已存在」是问题，更新模板里「不存在」才是问题——
	 * 两种模板对同一个事实的判断正好相反，所以不能塞进纯字段校验里。
	 */
	private List<GoodsImportIssueVO> checkCodeExistence(List<GoodsImportRowDTO> rows, String templateType) {
		String field = GoodsImportColumn.SPU_CODE.getField();
		Set<String> codes = rows.stream().map(row -> row.get(field))
				.filter(StringUtils::isNotBlank).collect(Collectors.toSet());
		if (codes.isEmpty()) {
			return List.of();
		}
		Set<String> existing = new HashSet<>(goodsSpuService.list(Wrappers.<GoodsSpu>lambdaQuery()
						.select(GoodsSpu::getSpuCode)
						.in(GoodsSpu::getSpuCode, codes))
				.stream().map(GoodsSpu::getSpuCode).toList());

		boolean update = GoodsImportTemplateType.UPDATE.getCode().equals(templateType);
		List<GoodsImportIssueVO> issues = new ArrayList<>();
		for (GoodsImportRowDTO row : rows) {
			String code = row.get(field);
			if (StringUtils.isBlank(code)) {
				continue;
			}
			boolean hit = existing.contains(code);
			if (update && !hit) {
				issues.add(GoodsImportIssueVO.of(row.getRowNo(), row.get(GoodsImportColumn.NAME.getField()),
						GoodsImportColumn.SPU_CODE.getHeader(), GoodsImportConstants.ISSUE_DUPLICATE,
						"商品编码不存在，无法更新"));
			} else if (!update && hit) {
				issues.add(GoodsImportIssueVO.of(row.getRowNo(), row.get(GoodsImportColumn.NAME.getField()),
						GoodsImportColumn.SPU_CODE.getHeader(), GoodsImportConstants.ISSUE_DUPLICATE,
						"商品编码已存在"));
			}
		}
		return issues;
	}

	/** 选了「覆盖更新」或本来就是更新模板时，编码类问题不再拦人 */
	private boolean isDuplicateWaived(GoodsImportIssueVO issue, String templateType, String dupStrategy) {
		if (!GoodsImportConstants.ISSUE_DUPLICATE.equals(issue.getType())) {
			return false;
		}
		if (GoodsImportTemplateType.UPDATE.getCode().equals(templateType)) {
			// 更新模板下「编码不存在」是真问题，不能豁免；「已存在」本来就是正常情况
			return !StringUtils.contains(issue.getReason(), "不存在");
		}
		return GoodsImportConstants.DUP_OVERWRITE.equals(dupStrategy);
	}

	private GoodsImportTaskVO toVO(GoodsImportTask task) {
		GoodsImportTaskVO vo = new GoodsImportTaskVO();
		vo.setId(task.getId());
		vo.setStatus(task.getStatus());
		vo.setTemplateType(task.getTemplateType());
		vo.setFileName(task.getFileName());
		vo.setCategoryName(task.getCategoryName());
		vo.setOperatorName(task.getOperatorName());
		vo.setTotalCount(task.getTotalCount());
		vo.setProcessedCount(task.getProcessedCount());
		vo.setSuccessCount(task.getSuccessCount());
		vo.setFailCount(task.getFailCount());
		vo.setErrorMsg(task.getErrorMsg());
		vo.setCreateTime(task.getCreateTime());
		vo.setStartTime(task.getStartTime());
		vo.setFinishTime(task.getFinishTime());
		vo.setDurationMs(task.getDurationMs());
		vo.setPercent(percentOf(task));
		return vo;
	}

	/**
	 * 进度百分比。运行态封顶 99：跑完最后一行到状态落库之间有个空档，
	 * 这段时间若返回 100，前端会先跳结果页再被下一次轮询打回，闪一下。
	 */
	private Integer percentOf(GoodsImportTask task) {
		if (GoodsImportConstants.STATUS_DRAFT.equals(task.getStatus())) {
			return 0;
		}
		if (!GoodsImportConstants.STATUS_RUNNING.equals(task.getStatus())) {
			return 100;
		}
		int total = task.getTotalCount() == null ? 0 : task.getTotalCount();
		int processed = task.getProcessedCount() == null ? 0 : task.getProcessedCount();
		if (total <= 0) {
			return 0;
		}
		return Math.min(99, (int) Math.floor(processed * 100.0 / total));
	}

	private GoodsImportTask getExisting(String taskId) {
		GoodsImportTask task = getById(taskId);
		if (task == null) {
			throw new BizException("导入任务不存在或已过期");
		}
		return task;
	}

	private String flag(String value, boolean defaultOn) {
		if (StringUtils.isBlank(value)) {
			return defaultOn ? "1" : "0";
		}
		return "1".equals(value) ? "1" : "0";
	}

	private String currentUsername() {
		BaseUser user = SecurityUtils.getUser();
		return user == null ? null : user.getUsername();
	}

	private List<GoodsImportRowDTO> readRows(String taskId) {
		String json = baseMapper.selectRowsJson(taskId);
		if (StringUtils.isBlank(json)) {
			return List.of();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<List<GoodsImportRowDTO>>() {
			});
		} catch (Exception e) {
			log.warn("读取导入行数据失败, taskId={}", taskId, e);
			return List.of();
		}
	}

	private List<GoodsImportIssueVO> readIssues(String taskId) {
		String json = baseMapper.selectFailJson(taskId);
		if (StringUtils.isBlank(json)) {
			return List.of();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<List<GoodsImportIssueVO>>() {
			});
		} catch (Exception e) {
			log.warn("读取导入失败明细失败, taskId={}", taskId, e);
			return List.of();
		}
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new BizException("导入数据过大或格式异常，无法保存");
		}
	}
}
