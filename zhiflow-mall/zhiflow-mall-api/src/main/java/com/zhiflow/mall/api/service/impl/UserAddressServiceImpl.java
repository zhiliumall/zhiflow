/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.mall.common.entity.UserAddress;
import com.zhiflow.mall.api.mapper.UserAddressMapper;
import com.zhiflow.mall.api.service.UserAddressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户收货地址
 *
 * @date 2019-09-11 14:28:59
 */
@Service
@Slf4j
public class UserAddressServiceImpl extends ServiceImpl<UserAddressMapper, UserAddress> implements UserAddressService {


	/**
	 * ⚠️ 必须重写 saveOrUpdate。
	 *
	 * <p>MyBatis-Plus 3.5.16 的 {@code AbstractRepository.saveOrUpdate(T)} 实现是
	 * <pre>return getBaseMapper().insertOrUpdate(entity);</pre>
	 * ——直接打到 mapper，<b>不再</b>像旧版那样转调 {@code this.save()} / {@code this.updateById()}。
	 * 于是本类下面的重写（清同用户旧默认地址的 {@link #setIsDefault}）在 {@code UserAddressController.save}
	 * 这条唯一的 C 端写入路径上
	 * 被<b>整个绕过</b>，而且悄无声息：接口照常返回 code 0。
	 *
	 * <p>2026-09-08 实测复现：把 A 地址存成默认后，原默认 B 仍是默认，
	 * 一个用户同时有多条 is_default='1'（真实数据里出现过）；
	 * {@code pg_stat_statements} 里也确实找不到那条 {@code set is_default=0 where ... and is_default=1}。
	 *
	 * <p>这里把分支写回来，转调本类被重写的方法。重写的方法是普通方法、没挂 AOP 注解，
	 * 自调用不会丢代理，所以这样写是安全的。
	 * （反例见 GoodsSkuServiceImpl.updateById——那个挂了 @DistributedLock，
	 * 必须由调用方经 bean 代理调用，不能靠自调用兜。）
	 */
	@Override
	public boolean saveOrUpdate(UserAddress entity) {
		if (entity == null) {
			return false;
		}
		boolean exists = StrUtil.isNotBlank(entity.getId()) && getById(entity.getId()) != null;
		return exists ? updateById(entity) : save(entity);
	}

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
