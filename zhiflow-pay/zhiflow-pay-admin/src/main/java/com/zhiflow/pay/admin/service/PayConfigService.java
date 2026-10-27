/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.pay.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.pay.common.entity.PayConfig;

/**
 * 支付配置
 *
 * @date 2020-05-29 16:37:13
 */
public interface PayConfigService extends IService<PayConfig> {
	/**
	 * 通过appId获取
	 * @param appId
	 * @return
	 */
	PayConfig findByAppId(String appId);

	/**
	 * 通过type获取
	 * @param type
	 * @return
	 */
	PayConfig findByType(String type);

	/**
	 * 通过appId和type获取
	 * @param appId 小程序appId
	 * @param type 支付类型
	 * @return
	 */
	PayConfig findByAppIdAndType(String appId, String type);
}
