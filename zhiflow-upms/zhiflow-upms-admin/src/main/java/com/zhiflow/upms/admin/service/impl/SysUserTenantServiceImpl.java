/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.upms.common.entity.SysUserTenant;
import com.zhiflow.upms.admin.mapper.SysUserTenantMapper;
import com.zhiflow.upms.admin.service.SysUserTenantService;
import org.springframework.stereotype.Service;

/**
 * 系统管理员用户绑定租户关联表
 *
 * @date 2021-11-11 12:30:59
 */
@Service
public class SysUserTenantServiceImpl extends ServiceImpl<SysUserTenantMapper, SysUserTenant> implements SysUserTenantService {

}
