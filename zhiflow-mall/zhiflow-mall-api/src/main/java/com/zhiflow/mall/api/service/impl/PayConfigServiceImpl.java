/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.mall.api.mapper.PayConfigMapper;
import com.zhiflow.mall.api.service.PayConfigService;
import com.zhiflow.mall.common.enums.PaymentTypeEnum;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.pay.common.entity.PayConfig;
import com.zhiflow.pay.common.vo.PayConfigVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 支付配置服务实现
 *
 * @date 2025-01-19
 */
@Slf4j
@Service
public class PayConfigServiceImpl extends ServiceImpl<PayConfigMapper, PayConfig> implements PayConfigService {

	@Override
	public List<PayConfigVO> getPayConfigList(String appId) {
		var query = Wrappers.<PayConfig>lambdaQuery()
				.eq(PayConfig::getDelFlag, CommonConstants.STATUS_NORMAL);
		if (appId != null && !appId.isEmpty()) {
			// appId 是公众号的 AppID，只用来挑微信支付配置；支付宝等其它渠道的 app_id 是各自平台的应用号，
			// 不参与匹配，否则微信内带 appId 查询时支付宝配置会被一并滤掉
			query.and(w -> w.ne(PayConfig::getType, PaymentTypeEnum.WECHAT.getValue()).or().eq(PayConfig::getAppId, appId));
		}
		List<PayConfig> configList = list(query);
		return configList.stream().map(this::convertToVO).collect(Collectors.toList());
	}

	@Override
	public PayConfigVO getPayConfigByType(String type) {
		PayConfig config = getOne(Wrappers.<PayConfig>lambdaQuery()
				.eq(PayConfig::getType, type)
				.eq(PayConfig::getDelFlag, CommonConstants.STATUS_NORMAL));
		return config != null ? convertToVO(config) : null;
	}

	/**
	 * 转换为VO对象（不包含敏感字段）
	 *
	 * @param config 支付配置
	 * @return PayConfigVO
	 */
	private PayConfigVO convertToVO(PayConfig config) {
		PayConfigVO vo = new PayConfigVO();
		vo.setId(config.getId());
		vo.setType(config.getType());
		vo.setModel(config.getModel());
		vo.setAppId(config.getAppId());
		vo.setMchId(config.getMchId());
        vo.setDelFlag(config.getDelFlag());
		return vo;
	}
}
