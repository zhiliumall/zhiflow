/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.weixin.admin.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.weixin.common.entity.WxUser;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 微信用户
 *
 * @date 2019-03-25 15:39:39
 */
public interface WxUserMapper extends BaseMapper<WxUser> {

    /**
     * 新增指定租户的粉丝 / 消息分表。只建缺的表，不 DROP，对存量租户重复调用安全。
     * @param tenantId
     */
    @InterceptorIgnore(tenantLine ="true")
    void createTenantDataBaseTables(@Param("tenantId")String tenantId, @Param("dataBaseTables") List<String> dataBaseTables);
}
