/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.weixin.admin.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.weixin.common.entity.WxApp;

import java.util.List;

/**
 * 微信应用
 *
 * @date 2019-03-15 10:26:44
 */
public interface WxAppMapper extends BaseMapper<WxApp> {

	/**
	 * 通过appId获取WxApp，无租户条件
	 * @param appId
	 * @return
	 */
	@InterceptorIgnore(tenantLine="true")
	List<WxApp> findByAppId(String appId);

	/**
	 * 物理删除单行。
	 *
	 * <p>{@code wx_app} 的主键是微信 appId（{@code @TableId(IdType.INPUT)}），重新授权流程是
	 * 「先删旧行 → 立刻按同一 appId 重插」（{@code WxOpenAuthController.jump}、
	 * {@code WxOpenNotifyController} 的取消授权 + 快速注册回调）。软删后墓碑行占着主键，
	 * 而 {@code saveOrUpdate} 的 {@code getById} 被 {@code @TableLogic} 追加 {@code del_flag='0'}
	 * 后看不见墓碑 → 走 insert 分支 → 主键冲突 500，且重试永远走同一条路，授权通道永久卡死。
	 * <b>主键做不成部分唯一索引，V80 对这种情况没有保护。</b>
	 * {@code wx_app} 已登记在 {@code PhysicalDeleteRegistry}。
	 */
	@org.apache.ibatis.annotations.Delete("delete from wx_app where id = #{id}")
	int physicalRemoveById(@org.apache.ibatis.annotations.Param("id") String id);
}
