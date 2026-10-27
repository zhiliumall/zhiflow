/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.mall.common.entity.UserAddress;
import com.zhiflow.mall.admin.mapper.UserAddressMapper;
import com.zhiflow.mall.admin.service.UserAddressService;
import org.springframework.stereotype.Service;

/**
 * 用户收货地址
 *
 * @date 2019-09-11 14:28:59
 */
@Service
public class UserAddressServiceImpl extends ServiceImpl<UserAddressMapper, UserAddress> implements UserAddressService {

	@Override
	public boolean save(UserAddress entity) {
		setIsDefault(entity);
		return super.save(entity);
	}

	@Override
	public boolean updateById(UserAddress entity) {
		setIsDefault(entity);
		return super.updateById(entity);
	}

	/**
	 * 修改默认地址
	 * @param entity
	 */
	void setIsDefault(UserAddress entity){
		if(CommonConstants.YES.equals(entity.getIsDefault())){
			UserAddress userAddress = new UserAddress();
			userAddress.setIsDefault(CommonConstants.NO);
			super.update(userAddress, Wrappers.<UserAddress>lambdaQuery()
					.eq(UserAddress::getUserId,entity.getUserId())
					.eq(UserAddress::getIsDefault,CommonConstants.YES));
		}
	}
}
