/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.weixin.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.weixin.common.entity.WxApp;

/**
 * 微信应用
 *
 * @date 2019-03-15 10:26:44
 */
public interface WxAppService extends IService<WxApp> {

	/**
	 * 通过appId获取WxApp，无租户条件
	 * @param appId
	 * @return
	 */
	WxApp findByAppId(String appId);

	/**
	 * 裸物理删单条 wx_app（不级联删 wx_user、不清缓存）。
	 *
	 * <p>只给「删旧行后立刻按同一 appId 重插」的重新授权流程用，语义见实现类注释。
	 * 需要级联和缓存清理的走 {@link #removeById(java.io.Serializable)}。
	 */
	boolean physicalRemoveById(String id);
}
