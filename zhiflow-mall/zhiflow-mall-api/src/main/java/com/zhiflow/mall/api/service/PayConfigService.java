/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.pay.common.entity.PayConfig;
import com.zhiflow.pay.common.vo.PayConfigVO;

import java.util.List;

/**
 * 支付配置服务
 *
 * @date 2025-01-19
 */
public interface PayConfigService extends IService<PayConfig> {

	/**
	 * 查询所有支付配置（不包含敏感字段）
	 *
	 * @return 支付配置列表
	 */
	List<PayConfigVO> getPayConfigList(String appId);

	/**
	 * 通过type获取支付配置（不包含敏感字段）
	 *
	 * @param type 支付类型
	 * @return 支付配置
	 */
	PayConfigVO getPayConfigByType(String type);
}
