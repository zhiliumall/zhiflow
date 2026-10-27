/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.upms.common.entity.SysUserTenant;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 系统管理员用户绑定租户关联表
 *
 * @date 2021-11-11 12:30:59
 */
public interface SysUserTenantMapper extends BaseMapper<SysUserTenant> {

	List<String>  listTenantId(@Param("userId") String userId);

	/**
	 * C-09：批量查询多个用户绑定的租户ID（消除用户分页 N+1），保留 is_default DESC 顺序
	 *
	 * @param userIds 用户ID列表
	 * @return 每行含 userId / tenantId
	 */
	List<Map<String, Object>> listUserTenantsByUserIds(@Param("userIds") List<String> userIds);
}
