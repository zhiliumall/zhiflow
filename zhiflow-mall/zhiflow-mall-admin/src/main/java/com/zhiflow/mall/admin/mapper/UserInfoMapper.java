/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zhiflow.mall.common.entity.UserInfo;
import org.apache.ibatis.annotations.Param;

/**
 * 商城用户
 *
 * @date 2019-12-04 11:09:55
 */
public interface UserInfoMapper extends BaseMapper<UserInfo> {

    IPage<UserInfo> selectPage1(IPage<UserInfo> page, @Param("query") UserInfo userInfo);
}
