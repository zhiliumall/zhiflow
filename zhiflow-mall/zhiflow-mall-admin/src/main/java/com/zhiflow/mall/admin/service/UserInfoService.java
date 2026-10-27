/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.UserInfo;

import java.util.List;
import java.util.Map;

/**
 * 商城用户
 *
 * @date 2019-12-04 11:09:55
 */
public interface UserInfoService extends IService<UserInfo> {

	IPage<UserInfo> page1(IPage<UserInfo> page, UserInfo userInfo);
}
