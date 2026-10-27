/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.lock.annotation.DistributedLock;
import com.zhiflow.common.lock.enums.LockTimeoutStrategy;
import com.zhiflow.mall.admin.mapper.UserInfoMapper;
import com.zhiflow.mall.admin.service.UserInfoService;
import com.zhiflow.mall.common.entity.UserInfo;
import org.springframework.stereotype.Service;

import java.io.Serializable;

/**
 * 商城用户
 *
 * @date 2019-12-04 11:09:55
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements UserInfoService {

	@Override
	@DistributedLock(name = "UserInfo", keys = {"#entity.getId()"}, lockTimeoutStrategy = LockTimeoutStrategy.FAIL_FAST)
	public boolean updateById(UserInfo entity) {
		return super.updateById(entity);
	}

	/**
	 * 后台删除商城用户 —— <b>2026-07-25 从物理删改为软删</b>。
	 *
	 * <p>原实现 {@code super.removeById(id)} 是 MyBatis-Plus 默认的物理 DELETE，只删
	 * {@code user_info} 主行、不管订单/余额/积分/地址/优惠券等从表。C 端注销走的是同一个坑，
	 * 实测会留下孤儿订单。
	 *
	 * <p>{@code user_info} 没有挂 {@code @TableLogic}（全仓仅 PromoHomeBanner 有），
	 * 所以这里显式写 {@code del_flag='1'} 而不是靠框架。
	 *
	 * <p>与 C 端注销的区别：这里<b>不做脱敏</b>。C 端是用户主动注销，需要清手机号、
	 * 昵称置「已注销用户」；后台删除是管理动作，保留原始资料以便需要时恢复。
	 */
	@Override
	@DistributedLock(name = "UserInfo", keys = {"#id"}, lockTimeoutStrategy = LockTimeoutStrategy.FAIL_FAST)
	public boolean removeById(Serializable id) {
		return super.update(new UpdateWrapper<UserInfo>().lambda()
				.set(UserInfo::getDelFlag, CommonConstants.STATUS_DEL)
				.eq(UserInfo::getId, id));
	}

	@Override
	public IPage<UserInfo> page1(IPage<UserInfo> page, UserInfo userInfo) {
		return baseMapper.selectPage1(page, userInfo);
	}
}
