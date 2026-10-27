/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.admin.controller;

import com.zhiflow.upms.common.dto.StorageCredentials;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.core.util.SensitiveUtils;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.upms.common.entity.SysConfigStorage;
import com.zhiflow.upms.admin.service.SysConfigStorageService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.BeanUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 存储配置
 *
 * @date 2020-02-03 20:07:38
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/configstorage")
@Tag(description = "configstorage", name = "存储配置管理")
public class SysConfigStorageController {

    private final SysConfigStorageService sysConfigStorageService;

    /**
     * 存储配置修改
     * @param sysConfigStorage 存储配置
     * @return R
     */
	@Operation(summary = "存储配置修改")
    @SysLog("修改存储配置")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('sys:configstorage:edit')")
    public R updateById(@RequestBody SysConfigStorage sysConfigStorage) {
		if(StrUtil.isNotBlank(sysConfigStorage.getId())){
			SysConfigStorage sysConfigStorageOld = sysConfigStorageService.getById(sysConfigStorage.getId());
			if (sysConfigStorageOld == null) {
				return R.failed("存储配置不存在或已删除，请刷新页面后重试");
			}
			// 页面回显的是打码值：提交空值或原样的打码值 = 不修改，沿用库里的旧值
			sysConfigStorage.setAccessKeyId(keepIfUnchanged(sysConfigStorageOld.getAccessKeyId(), sysConfigStorage.getAccessKeyId()));
			sysConfigStorage.setAccessKeySecret(keepIfUnchanged(sysConfigStorageOld.getAccessKeySecret(), sysConfigStorage.getAccessKeySecret()));
			sysConfigStorageService.updateById(sysConfigStorage);
		}else{
			sysConfigStorageService.save(sysConfigStorage);
		}
		// 响应体同样打码：此前这里把明文 accessKeySecret 原样回给浏览器
		return R.ok(masked(sysConfigStorage));
    }

	/**
	 * 查询存储配置
	 * @return R
	 */
	@Operation(summary = "查询存储配置")
	@GetMapping()
	@PreAuthorize("@ato.hasAuthority('sys:configstorage:get')")
	public R get() {
		SysConfigStorage sysConfigStorage = sysConfigStorageService.getOne(Wrappers.emptyWrapper());
		return R.ok(sysConfigStorage == null ? null : masked(sysConfigStorage));
	}

	/** AK / SK 打码后的副本（不改入参，入参可能是缓存里的对象）。 */
	static SysConfigStorage masked(SysConfigStorage source) {
		SysConfigStorage copy = new SysConfigStorage();
		BeanUtils.copyProperties(source, copy);
		copy.setAccessKeyId(SensitiveUtils.process(source.getAccessKeyId()));
		copy.setAccessKeySecret(SensitiveUtils.process(source.getAccessKeySecret()));
		return copy;
	}

	/**
	 * 保存时密钥字段的取值：提交为空，或等于旧值的打码形式 = 不修改（返回旧值）；否则为新值。
	 */
	static String keepIfUnchanged(String oldValue, String submitted) {
		if (StrUtil.isBlank(submitted)) {
			return oldValue;
		}
		if (oldValue != null && submitted.equals(SensitiveUtils.process(oldValue))) {
			return oldValue;
		}
		return submitted;
	}

	/**
	 * 查询存储配置缓存（服务间调用）
	 * @return R
	 */
	@Inside
	@Operation(summary = "存储配置查询")
	@GetMapping("/inside")
	public R<StorageCredentials> getInside() {
		return R.ok(StorageCredentials.from(sysConfigStorageService.getOne()));
	}

}
