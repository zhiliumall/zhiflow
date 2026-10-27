/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.upms.common.entity.SysConfigStorage;

/**
 * 存储配置
 *
 * @date 2020-02-03 20:07:38
 */
public interface SysConfigStorageService extends IService<SysConfigStorage> {

	SysConfigStorage getOne();
}
