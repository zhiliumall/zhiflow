/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.zhiflow.mall.admin.support.FileDownloads;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.mall.admin.service.GoodsExportService;
import com.zhiflow.mall.admin.service.GoodsImportExcelService;
import com.zhiflow.mall.admin.service.GoodsImportTaskService;
import com.zhiflow.mall.common.dto.GoodsImportRetryDTO;
import com.zhiflow.mall.common.dto.GoodsImportStartDTO;
import com.zhiflow.mall.common.entity.GoodsImportTask;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.enums.GoodsImportColumn;
import com.zhiflow.mall.common.enums.GoodsImportTemplateType;
import com.zhiflow.mall.common.vo.GoodsImportTaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 商品批量导入。
 *
 * <p>鉴权沿用商品自身的权限位：导入=新增（{@code mall:goodsspu:add}），
 * 记录/进度=列表（{@code mall:goodsspu:index}）。不另立一套导入专属权限，
 * 否则菜单权限配置得改一遍，而语义上「能批量建商品」本来就等价于「能建商品」。
 *
 * <p>表达式必须写 {@code @ato.hasAuthority}，{@code @pms.hasPermission} 在本项目是死代码，
 * 照抄会在首次调用时 SpEL 求值直接 500。
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/goodsimport")
@Tag(description = "goodsimport", name = "商品批量导入")
public class GoodsImportController {

	private final GoodsImportTaskService goodsImportTaskService;
	private final GoodsImportExcelService goodsImportExcelService;
	private final GoodsExportService goodsExportService;

	/**
	 * 模板下载。type=add 全量新增 / update 增量更新。
	 */
	@Operation(summary = "下载导入模板")
	@GetMapping("/template")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:add')")
	public ResponseEntity<byte[]> template(@RequestParam(value = "type", required = false) String type) {
		byte[] bytes = goodsImportExcelService.buildTemplate(type);
		return FileDownloads.attachment(bytes, goodsImportExcelService.templateFileName(type));
	}

	/**
	 * 模板字段说明。前端用它渲染「含 N 个字段」与字段清单，避免前后端各维护一份列表。
	 */
	@Operation(summary = "模板字段说明")
	@GetMapping("/template/columns")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:add')")
	public R columns(@RequestParam(value = "type", required = false) String type) {
		return R.ok(GoodsImportColumn.describe(GoodsImportTemplateType.of(type).getCode()));
	}

	/**
	 * 上传并预校验，返回草稿任务与异常明细。此时还没有写入任何商品。
	 */
	@Operation(summary = "上传预校验")
	// 预校验虽然不写商品，但会落一条草稿任务（GoodsImportTask），且它是整条导入链的入口——
	// 事后追「这批商品是谁在什么时候导的」必须从这一步起就有记录，否则只能看到 start 之后的部分。
	@SysLog("商品导入预校验")
	@PostMapping("/precheck")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:add')")
	public R precheck(@RequestPart("file") MultipartFile file, GoodsImportTask form) {
		return R.ok(goodsImportTaskService.precheck(file, form));
	}

	@Operation(summary = "开始导入")
	@SysLog("批量导入商品")
	@PostMapping("/task/{id}/start")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:add')")
	public R start(@PathVariable("id") String id, @RequestBody GoodsImportStartDTO options) {
		return R.ok(goodsImportTaskService.start(id, options));
	}

	@Operation(summary = "导入进度/结果")
	@GetMapping("/task/{id}")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:index')")
	public R detail(@PathVariable("id") String id) {
		return R.ok(goodsImportTaskService.detail(id));
	}

	@Operation(summary = "终止导入")
	@SysLog("终止商品批量导入")
	@PostMapping("/task/{id}/abort")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:add')")
	public R abort(@PathVariable("id") String id) {
		goodsImportTaskService.abort(id);
		return R.ok();
	}

	@Operation(summary = "导入记录分页")
	@GetMapping("/task/page")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:index')")
	public R page(Page<GoodsImportTask> page,
				  @RequestParam(value = "status", required = false) String status,
				  @RequestParam(value = "days", required = false) Integer days) {
		return R.ok(goodsImportTaskService.pageRecords(page, status, days));
	}

	/**
	 * 商品列表工具栏的「进行中任务」角标。刷列表时顺带调一次，别单独开轮询。
	 */
	@Operation(summary = "进行中的导入任务")
	@GetMapping("/task/running")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:index')")
	public R running() {
		return R.ok(goodsImportTaskService.listRunning());
	}

	@Operation(summary = "下载失败数据")
	@GetMapping("/task/{id}/fail-export")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:index')")
	public ResponseEntity<byte[]> exportFail(@PathVariable("id") String id) {
		byte[] bytes = goodsImportTaskService.exportFail(id);
		GoodsImportTaskVO task = goodsImportTaskService.detail(id);
		String base = StringUtils.defaultIfBlank(task.getFileName(), "导入数据");
		base = StringUtils.removeEndIgnoreCase(StringUtils.removeEndIgnoreCase(base, ".xlsx"), ".xls");
		return FileDownloads.attachment(bytes, base + "_失败数据.xlsx");
	}

	/**
	 * 导出现有商品，格式 = 增量更新模板，改完可直接回传导入。
	 *
	 * <p>查询条件对象与商品列表 {@code /goodsspu/page} 同款（Spring 按同名参数绑定到 {@link GoodsSpu}），
	 * 所以「列表里筛出什么就导出什么」；勾选了商品则 {@code ids} 优先，忽略筛选条件。
	 *
	 * <p>权限取列表位而非新增位：导出本身只读，能看列表就能导；
	 * 改完要生效仍得过导入那道 {@code mall:goodsspu:add}。
	 */
	@Operation(summary = "导出商品（增量更新模板格式）")
	@SysLog("导出商品")
	@GetMapping("/export")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:index')")
	public ResponseEntity<byte[]> export(GoodsSpu query,
										 @RequestParam(value = "ids", required = false) String ids) {
		List<String> idList = StringUtils.isBlank(ids) ? List.of()
				: Arrays.stream(ids.split(",")).map(String::trim).filter(StringUtils::isNotBlank).toList();
		byte[] bytes = goodsExportService.export(query, idList);
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
		return FileDownloads.attachment(bytes, "商品导出_" + stamp + ".xlsx");
	}

	/**
	 * 失败行修正后重新导入：新建一个任务立即开跑，原任务保持只读。
	 */
	@Operation(summary = "重新导入失败行")
	@SysLog("重新导入商品失败行")
	@PostMapping("/task/{id}/retry")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:add')")
	public R retry(@PathVariable("id") String id, @RequestBody GoodsImportRetryDTO body) {
		return R.ok(goodsImportTaskService.retry(id, body == null ? null : body.getRows()));
	}

}
