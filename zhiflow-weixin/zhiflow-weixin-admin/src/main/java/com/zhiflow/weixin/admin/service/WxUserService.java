/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.weixin.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.weixin.common.dto.WxOpenDataDTO;
import com.zhiflow.weixin.common.entity.WxUser;

/**
 * 微信用户
 *
 * @date 2019-03-25 15:39:39
 */
public interface WxUserService extends IService<WxUser> {

	/**
	 * 根据openId获取用户
	 * @param appId
	 * @param openId
	 * @return
	 */
	WxUser getByOpenId(String appId, String openId);

	/**
	 * 通过公众号网页授权更新微信用户
	 * @param wxOpenDataDTO
	 */
	WxUser updateByMpInside(WxOpenDataDTO wxOpenDataDTO);
}
