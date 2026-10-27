/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.UserAddress;

/**
 * 用户收货地址
 *
 * @date 2019-09-11 14:28:59
 */
public interface UserAddressMapper extends BaseMapper<UserAddress> {

	/**
	 * 迁移用户收货地址数据（用于账号合并）
	 * @param oldUserId 原用户ID
	 * @param newUserId 新用户ID
	 * @return 更新记录数
	 */
	@org.apache.ibatis.annotations.Update("UPDATE user_address SET user_id = #{newUserId} WHERE user_id = #{oldUserId}")
	int updateUserId(@org.apache.ibatis.annotations.Param("oldUserId") String oldUserId, @org.apache.ibatis.annotations.Param("newUserId") String newUserId);

}
