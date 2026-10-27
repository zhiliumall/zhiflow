package com.zhiflow.upms.admin.controller;

import com.zhiflow.upms.admin.projection.UpmsInsideProjections;

import com.zhiflow.upms.common.dto.SysLogPayload;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.upms.admin.service.SysLogService;
import com.zhiflow.upms.common.entity.SysLog;
import com.zhiflow.upms.common.vo.PreLogVO;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.annotation.Inside;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * <p>
 * 日志表 前端控制器
 * </p>
 *
 * @author
 */
@RestController
@AllArgsConstructor
@RequestMapping("/log")
@Tag(description = "log", name = "日志管理模块")
public class SysLogController {
	private final SysLogService sysLogService;

	/**
	 * 简单分页查询
	 *
	 * @param page   分页对象
	 * @param sysLog 系统日志
	 * @return
	 */
	@Operation(summary = "分页查询")
	@GetMapping("/page")
//	@PreAuthorize("@ato.hasAuthority('sys:log:index')")
	public R getLogPage(Page page, SysLog sysLog) {
		return R.ok(sysLogService.page(page, Wrappers.query(sysLog)));
	}

	/**
	 * 删除日志
	 *
	 * @param id ID
	 * @return
	 */
	@Operation(summary = "删除日志")
	@com.zhiflow.common.log.annotation.SysLog("删除日志")
	@DeleteMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('sys:log:del')")
	public R removeById(@PathVariable String id) {
		return R.ok(sysLogService.removeById(id));
	}

	/**
	 * 插入日志
	 *
	 * @param sysLog 日志实体
	 * @return
	 */
	@Operation(summary = "插入日志")
	@Inside
	@PostMapping("/save")
	public R<Boolean> save(@Valid @RequestBody SysLogPayload sysLog) {
		// NECESSARY: @Inside internal calls may not have tenant context from filter
		// Falls back to system tenant for cross-service log persistence
		if(StrUtil.isEmpty(TenantContextHolder.getTenantId())) {
			TenantContextHolder.setTenantId(CommonConstants.SYSTEM_TENANT_ID);
		}
		return R.ok(sysLogService.save(UpmsInsideProjections.sysLog(sysLog)));
	}

	/**
	 * 批量插入前端异常日志
	 *
	 * @param preLogVOList 日志实体
	 * @return ok/false
	 */
	@Operation(summary = "批量插入异常日志")
	@com.zhiflow.common.log.annotation.SysLog("批量插入异常日志")
	@PostMapping("/logs")
	public R saveBatchLogs(@RequestBody List<PreLogVO> preLogVOList) {
		return R.ok(sysLogService.saveBatchLogs(preLogVOList));
	}
}
